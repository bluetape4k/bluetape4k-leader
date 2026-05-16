package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Candidate registry backed by Lettuce [StatefulRedisConnection].
 *
 * ## Storage Structure
 * - Key: `leader:strategy:candidates:{lockName}:{nodeId}`
 * - Value: String encoded by [LettuceCandidateInfoCodec]
 * - TTL: set via `PSETEX` to serve as a heartbeat (configured at registration time)
 *
 * ## Distributed Consistency Warning
 * [updateResult] is a read-modify-write operation and does not guarantee full atomicity.
 * In practice, collision risk is low because only the winner node updates its own entry.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec-based)
 */
internal class LettuceCandidateRegistry(
    private val connection: StatefulRedisConnection<String, String>,
) {
    companion object: KLogging() {
        /** Redis SCAN page size hint. This is a guide for the number of keys returned per page, not a hard upper bound. */
        private const val SCAN_PAGE_SIZE = 1000L
    }

    private val sync = connection.sync()

    private fun candidateKey(lockName: String, nodeId: String) =
        "leader:strategy:candidates:$lockName:$nodeId"

    private fun keyPattern(lockName: String) =
        "leader:strategy:candidates:$lockName:*"

    private fun validateLockName(lockName: String) {
        lockName.requireNotBlank("lockName")
        require(lockName.none { it in "*?[]\\" }) { "lockName must not contain SCAN glob metacharacters: $lockName" }
    }

    /**
     * Registers or refreshes a candidate entry.
     *
     * If [ttl] = [Duration.ZERO], the entry is stored permanently without a TTL.
     * In distributed environments, it is recommended to set TTL to at least twice the heartbeat interval.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) sync.set(key, value)
        else sync.psetex(key, ttl.inWholeMilliseconds, value)
    }

    /** Unregisters a candidate. A non-existent nodeId is silently ignored. */
    fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        sync.del(candidateKey(lockName, nodeId))
    }

    /**
     * Returns the current list of candidates registered under [lockName].
     *
     * Corrupted individual entries (invalid encoding, numeric parsing failures, etc.) are skipped
     * with a warning log, so that a single bad entry does not abort the entire election round.
     */
    fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val keys = scanKeys(lockName)
        if (keys.isEmpty()) return emptyList()
        return sync.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                val raw = if (kv.hasValue()) kv.value else return@mapNotNull null
                runCatching { LettuceCandidateInfoCodec.decode(raw) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
    }

    /**
     * Applies an action result to the candidate entry.
     *
     * Uses `SET key value XX KEEPTTL` to prevent zombie resurrection of expired keys.
     * If the key has already expired, the XX flag makes this a no-op.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = sync.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        sync.set(key, updated, SetArgs.Builder.xx().keepttl())
    }

    private fun scanKeys(lockName: String): List<String> {
        val args = ScanArgs.Builder.matches(keyPattern(lockName)).limit(SCAN_PAGE_SIZE)
        val keys = mutableListOf<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = sync.scan(cursor, args)
            keys += result.keys
            cursor = result
        } while (!cursor.isFinished)
        return keys
    }
}

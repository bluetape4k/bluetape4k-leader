package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration

/**
 * Candidate registry backed by Lettuce [StatefulRedisConnection].
 *
 * ## Storage Structure
 * - Index key: `leader:strategy:candidates:{lockName}` (Redis Set of node ids)
 * - Candidate key: `leader:strategy:candidates:{lockName}:{nodeId}`
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
        private const val KEY_PREFIX = "leader:strategy:candidates"
    }

    private val sync = connection.sync()

    private fun indexKey(lockName: String) =
        "$KEY_PREFIX:$lockName"

    private fun candidateKey(lockName: String, nodeId: String) =
        "${indexKey(lockName)}:$nodeId"

    private fun validateLockName(lockName: String) {
        lockName.requireNotBlank("lockName")
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
        val indexKey = indexKey(lockName)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) sync.set(key, value)
        else sync.psetex(key, ttl.inWholeMilliseconds, value)
        sync.sadd(indexKey, info.nodeId)
        if (ttl == Duration.ZERO) sync.persist(indexKey)
        else sync.pexpire(indexKey, ttl.inWholeMilliseconds)
    }

    /** Unregisters a candidate. A non-existent nodeId is silently ignored. */
    fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        sync.del(candidateKey(lockName, nodeId))
        sync.srem(indexKey(lockName), nodeId)
    }

    /**
     * Returns the current list of candidates registered under [lockName].
     *
     * Corrupted individual entries (invalid encoding, numeric parsing failures, etc.) are skipped
     * with a warning log, so that a single bad entry does not abort the entire election round.
     */
    fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val nodeIds = sync.smembers(indexKey(lockName)).toList()
        if (nodeIds.isEmpty()) return emptyList()
        val staleNodeIds = mutableListOf<String>()
        val keys = nodeIds.map { nodeId -> candidateKey(lockName, nodeId) }
        return sync.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                if (!kv.hasValue()) {
                    kv.key.removePrefix("${indexKey(lockName)}:").takeIf { it.isNotBlank() }?.let(staleNodeIds::add)
                    return@mapNotNull null
                }
                val raw = kv.value
                runCatching { LettuceCandidateInfoCodec.decode(raw) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
            .also {
                if (staleNodeIds.isNotEmpty()) {
                    sync.srem(indexKey(lockName), *staleNodeIds.toTypedArray())
                }
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
}

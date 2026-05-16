@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend candidate registry backed by the Lettuce coroutines API.
 *
 * This is the suspend variant of [LettuceCandidateRegistry] and uses [RedisCoroutinesCommands].
 * Because Lettuce uses Netty-based asynchronous I/O, no [kotlinx.coroutines.Dispatchers.IO] switch is required.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec-based)
 */
internal class LettuceSuspendCandidateRegistry(
    connection: StatefulRedisConnection<String, String>,
) {
    companion object: KLogging() {
        /** Redis SCAN page size hint. This is a guide for the number of keys returned per page, not a hard upper bound. */
        private const val SCAN_PAGE_SIZE = 1000L
    }

    private val cmds: RedisCoroutinesCommands<String, String> = connection.coroutines()

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
     */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) cmds.set(key, value)
        else cmds.psetex(key, ttl.inWholeMilliseconds, value)
    }

    /** Unregisters a candidate. A non-existent nodeId is silently ignored. */
    suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        cmds.del(candidateKey(lockName, nodeId))
    }

    /**
     * Returns the current list of candidates registered under [lockName].
     *
     * Corrupted individual entries (invalid encoding, numeric parsing failures, etc.) are skipped with a warning log.
     */
    suspend fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val keys = scanKeys(lockName)
        if (keys.isEmpty()) return emptyList()
        return cmds.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                if (!kv.hasValue()) return@mapNotNull null
                runCatching { LettuceCandidateInfoCodec.decode(kv.getValue()) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
            .toList()
    }

    /**
     * Applies an action result to the candidate entry.
     *
     * Uses `SET key value XX KEEPTTL` to prevent zombie resurrection of expired keys.
     */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = cmds.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        cmds.set(key, updated, SetArgs.Builder.xx().keepttl())
    }

    private suspend fun scanKeys(lockName: String): List<String> {
        val args = ScanArgs.Builder.matches(keyPattern(lockName)).limit(SCAN_PAGE_SIZE)
        val keys = mutableListOf<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = cmds.scan(cursor, args) ?: break
            keys += result.keys
            cursor = result
        } while (!cursor.isFinished)
        return keys
    }
}

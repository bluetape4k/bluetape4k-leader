@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.logging.warn
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration

/**
 * `LettuceSuspendCandidateRegistry`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal class LettuceSuspendCandidateRegistry(
    connection: StatefulRedisConnection<String, String>,
) {
    companion object: KLogging() {
        private const val KEY_PREFIX = "leader:strategy:candidates"
    }

    private val cmds: RedisCoroutinesCommands<String, String> = connection.coroutines()

    private fun indexKey(lockName: String) =
        "$KEY_PREFIX:$lockName"

    private fun candidateKey(lockName: String, nodeId: String) =
        "${indexKey(lockName)}:$nodeId"

    private fun validateLockName(lockName: String) {
        lockName.requireNotBlank("lockName")
    }

    /**
     * `registerCandidate` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val indexKey = indexKey(lockName)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) cmds.set(key, value)
        else cmds.psetex(key, ttl.inWholeMilliseconds, value)
        cmds.sadd(indexKey, info.nodeId)
        if (ttl == Duration.ZERO) cmds.persist(indexKey)
        else cmds.pexpire(indexKey, ttl.inWholeMilliseconds)
    }

    /**
     * `unregisterCandidate` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        cmds.del(candidateKey(lockName, nodeId))
        cmds.srem(indexKey(lockName), nodeId)
    }

    /**
     * `listCandidates` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val nodeIds = cmds.smembers(indexKey(lockName)).toList()
        if (nodeIds.isEmpty()) return emptyList()
        val staleNodeIds = mutableListOf<String>()
        val keys = nodeIds.map { nodeId -> candidateKey(lockName, nodeId) }
        return cmds.mget(*keys.toTypedArray())
            .mapNotNull { kv ->
                if (!kv.hasValue()) {
                    kv.key.removePrefix("${indexKey(lockName)}:").takeIf { it.isNotBlank() }?.let(staleNodeIds::add)
                    return@mapNotNull null
                }
                runCatching { LettuceCandidateInfoCodec.decode(kv.getValue()) }
                    .onFailure { log.warn(it) { "[$lockName] CandidateInfo 디코딩 실패 — 항목 skip: key=${kv.key}" } }
                    .getOrNull()
            }
            .toList()
            .also {
                if (staleNodeIds.isNotEmpty()) {
                    cmds.srem(indexKey(lockName), *staleNodeIds.toTypedArray())
                }
            }
    }

    /**
     * `updateResult` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        val current = cmds.get(key)?.let { LettuceCandidateInfoCodec.decode(it) } ?: return
        val updated = LettuceCandidateInfoCodec.encode(current.withResult(result))
        cmds.set(key, updated, SetArgs.Builder.xx().keepttl())
    }
}

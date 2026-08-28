@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.validateLockName
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import java.time.Instant
import kotlin.time.Duration

/**
 * `LettuceSuspendCandidateRegistry`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal class LettuceSuspendCandidateRegistry(
    connection: StatefulRedisConnection<String, String>,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) {
    /** 기존 internal JVM constructor descriptor를 보존합니다. */
    internal constructor(connection: StatefulRedisConnection<String, String>) : this(
        connection,
        DEFAULT_KEY_PREFIX,
    )

    companion object {
        internal const val DEFAULT_KEY_PREFIX = "leader:strategy:candidates"
        internal const val GROUP_KEY_PREFIX = "leader:strategy:group-candidates:lettuce:v1"
    }

    private val cmds: RedisCoroutinesCommands<String, String> = connection.coroutines()
    private val asyncCommands by lazy { connection.async() }

    private fun indexKey(lockName: String) =
        "$keyPrefix:$lockName"

    private fun candidateKey(lockName: String, nodeId: String) =
        "${indexKey(lockName)}:$nodeId"

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
        // 후보별 TTL은 candidate key에만 적용한다. index set까지 만료시키면
        // 유한 TTL 후보가 영구 후보를 같은 lockName에서 가릴 수 있다.
        cmds.persist(indexKey)
    }

    /** 기존 후보의 heartbeat를 원자적으로 갱신하고 결과 통계는 보존합니다. */
    suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val reply = RedisScriptRunner.runSuspending<List<Any>>(
            asyncCommands,
            LettuceCandidateRefreshScript.REFRESH,
            ScriptOutputType.MULTI,
            arrayOf(candidateKey(lockName, info.nodeId), indexKey(lockName)),
            LettuceCandidateInfoCodec.encode(info),
            ttl.inWholeMilliseconds.toString(),
        )
        LettuceCandidateRefreshScript.rethrowMalformed(reply)
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
                LettuceCandidateInfoCodec.decode(kv.getValue())
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
        val reply = RedisScriptRunner.runSuspending<List<Any>>(
            asyncCommands,
            LettuceCandidateResultScript.UPDATE,
            ScriptOutputType.MULTI,
            arrayOf(key),
            *LettuceCandidateResultScript.resultArgs(result, Instant.now().toEpochMilli()),
        )
        LettuceCandidateResultScript.rethrowMalformed(reply)
    }
}

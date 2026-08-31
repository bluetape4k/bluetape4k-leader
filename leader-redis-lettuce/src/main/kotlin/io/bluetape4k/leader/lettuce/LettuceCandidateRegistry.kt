package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Instant
import kotlin.time.Duration

/**
 * `LettuceCandidateRegistry`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class LettuceCandidateRegistry(
    private val connection: StatefulRedisConnection<String, String>,
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

    private val sync = connection.sync()

    private fun indexKey(lockName: String) =
        LettuceCandidateKeyCodec.indexKey(keyPrefix, lockName)

    private fun candidateKey(lockName: String, nodeId: String) =
        LettuceCandidateKeyCodec.candidateKey(keyPrefix, lockName, nodeId)

    /**
     * `registerCandidate` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        val indexKey = indexKey(lockName)
        val value = LettuceCandidateInfoCodec.encode(info)
        if (ttl == Duration.ZERO) sync.set(key, value)
        else sync.psetex(key, ttl.inWholeMilliseconds, value)
        sync.sadd(indexKey, info.nodeId)
        // 후보별 TTL은 candidate key에만 적용한다. index set까지 만료시키면
        // 유한 TTL 후보가 영구 후보를 같은 lockName에서 가릴 수 있다.
        sync.persist(indexKey)
    }

    /** 기존 후보의 heartbeat를 원자적으로 갱신하고 결과 통계는 보존합니다. */
    fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val key = candidateKey(lockName, info.nodeId)
        if (sync.get(key) == null) {
            migrateLegacyCandidate(lockName, info.nodeId)
        }
        val reply = RedisScriptRunner.run<List<Any>>(
            sync,
            LettuceCandidateRefreshScript.REFRESH,
            ScriptOutputType.MULTI,
            arrayOf(key, indexKey(lockName)),
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
    fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        sync.del(candidateKey(lockName, nodeId))
        sync.srem(indexKey(lockName), nodeId)
        val legacyKey = LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId)
        if (readLegacyCandidate(legacyKey, nodeId) != null) {
            sync.del(legacyKey)
        }
        removeLegacyIndexMembers(lockName, listOf(nodeId))
    }

    /**
     * `listCandidates` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val currentIndexKey = indexKey(lockName)
        val currentNodeIds = sync.smembers(currentIndexKey).toList()
        val legacyNodeIds = readLegacyNodeIds(lockName)
        if (currentNodeIds.isEmpty() && legacyNodeIds.isEmpty()) return emptyList()

        val candidates = linkedMapOf<String, CandidateInfo>()
        val staleCurrentNodeIds = mutableListOf<String>()
        val currentKeys = currentNodeIds.associateBy { nodeId -> candidateKey(lockName, nodeId) }
        if (currentKeys.isNotEmpty()) {
            sync.mget(*currentKeys.keys.toTypedArray()).forEach { kv ->
                val nodeId = currentKeys[kv.key] ?: return@forEach
                if (!kv.hasValue()) {
                    staleCurrentNodeIds += nodeId
                    return@forEach
                }
                val candidate = LettuceCandidateInfoCodec.decode(kv.value)
                if (candidate.nodeId == nodeId) candidates[nodeId] = candidate
                else staleCurrentNodeIds += nodeId
            }
        }

        val staleLegacyNodeIds = mutableListOf<String>()
        legacyNodeIds.forEach { nodeId ->
            if (candidates.containsKey(nodeId)) return@forEach
            val legacyCandidate = readLegacyCandidate(
                LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId),
                nodeId,
            )
            if (legacyCandidate == null) {
                staleLegacyNodeIds += nodeId
                return@forEach
            }
            migrateLegacyCandidate(lockName, nodeId)
            candidates[nodeId] = legacyCandidate
        }

        if (staleCurrentNodeIds.isNotEmpty()) {
            sync.srem(currentIndexKey, *staleCurrentNodeIds.toTypedArray())
        }
        if (staleLegacyNodeIds.isNotEmpty()) {
            removeLegacyIndexMembers(lockName, staleLegacyNodeIds)
        }
        return candidates.values.toList()
    }

    /**
     * `updateResult` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        if (sync.get(key) == null) {
            migrateLegacyCandidate(lockName, nodeId)
        }
        val reply = RedisScriptRunner.run<List<Any>>(
            sync,
            LettuceCandidateResultScript.UPDATE,
            ScriptOutputType.MULTI,
            arrayOf(key),
            *LettuceCandidateResultScript.resultArgs(result, Instant.now().toEpochMilli()),
        )
        LettuceCandidateResultScript.rethrowMalformed(reply)
    }

    private fun readLegacyNodeIds(lockName: String): Set<String> = try {
        sync.smembers(LettuceCandidateKeyCodec.legacyIndexKey(keyPrefix, lockName))
    } catch (e: RedisCommandExecutionException) {
        if (e.isWrongType()) emptySet() else throw e
    }

    private fun readLegacyCandidate(key: String, expectedNodeId: String): CandidateInfo? {
        val raw = try {
            sync.get(key)
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
            null
        }
        return raw?.let(LettuceCandidateInfoCodec::decode)?.takeIf { it.nodeId == expectedNodeId }
    }

    private fun migrateLegacyCandidate(lockName: String, nodeId: String): Boolean {
        val legacyKey = LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId)
        val candidate = readLegacyCandidate(legacyKey, nodeId) ?: return false
        val raw = LettuceCandidateInfoCodec.encode(candidate)
        val ttl = sync.pttl(legacyKey)
        val migrated = when {
            ttl == -2L -> false
            ttl == -1L -> sync.setnx(candidateKey(lockName, nodeId), raw)
            ttl > 0L -> sync.set(candidateKey(lockName, nodeId), raw, SetArgs.Builder.nx().px(ttl)) != null
            else -> false
        }
        if (migrated) {
            sync.sadd(indexKey(lockName), nodeId)
            sync.persist(indexKey(lockName))
        }
        return migrated
    }

    private fun removeLegacyIndexMembers(lockName: String, nodeIds: List<String>) {
        try {
            sync.srem(LettuceCandidateKeyCodec.legacyIndexKey(keyPrefix, lockName), *nodeIds.toTypedArray())
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
        }
    }
}

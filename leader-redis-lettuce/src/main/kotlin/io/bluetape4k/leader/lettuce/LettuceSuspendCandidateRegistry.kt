@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.validateLockName
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.toList
import java.time.Instant
import kotlin.time.Duration

/**
 * `LettuceSuspendCandidateRegistry`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
@Suppress("TooManyFunctions")
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
        LettuceCandidateKeyCodec.indexKey(keyPrefix, lockName)

    private fun candidateKey(lockName: String, nodeId: String) =
        LettuceCandidateKeyCodec.candidateKey(keyPrefix, lockName, nodeId)

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
        val key = candidateKey(lockName, info.nodeId)
        if (cmds.get(key) == null) {
            migrateLegacyCandidate(lockName, info.nodeId)
        }
        val reply = RedisScriptRunner.runSuspending<List<Any>>(
            asyncCommands,
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
    suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        cmds.del(candidateKey(lockName, nodeId))
        cmds.srem(indexKey(lockName), nodeId)
        val legacyKey = LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId)
        if (readLegacyCandidate(legacyKey, nodeId) != null) {
            cmds.del(legacyKey)
        }
        removeLegacyIndexMembers(lockName, listOf(nodeId))
    }

    /**
     * `listCandidates` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("CyclomaticComplexMethod")
    suspend fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val currentIndexKey = indexKey(lockName)
        val currentNodeIds = cmds.smembers(currentIndexKey).toList()
        val legacyNodeIds = readLegacyNodeIds(lockName)
        if (currentNodeIds.isEmpty() && legacyNodeIds.isEmpty()) return emptyList()

        val candidates = linkedMapOf<String, CandidateInfo>()
        val missingCurrentNodeIds = mutableListOf<String>()
        val mismatchedCurrentNodeIds = mutableListOf<String>()
        val currentKeys = currentNodeIds.associateBy { nodeId -> candidateKey(lockName, nodeId) }
        if (currentKeys.isNotEmpty()) {
            cmds.mget(*currentKeys.keys.toTypedArray()).toList().forEach { kv ->
                val nodeId = currentKeys[kv.key] ?: return@forEach
                if (!kv.hasValue()) {
                    missingCurrentNodeIds += nodeId
                    return@forEach
                }
                val candidate = LettuceCandidateInfoCodec.decode(kv.getValue())
                if (candidate.nodeId == nodeId) candidates[nodeId] = candidate
                else mismatchedCurrentNodeIds += nodeId
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
            val currentCandidate = readCurrentCandidate(lockName, nodeId)
            if (currentCandidate != null) {
                cmds.sadd(currentIndexKey, nodeId)
                cmds.persist(currentIndexKey)
                candidates[nodeId] = currentCandidate
            } else {
                candidates[nodeId] = legacyCandidate
            }
        }

        if (mismatchedCurrentNodeIds.isNotEmpty()) {
            cmds.srem(currentIndexKey, *mismatchedCurrentNodeIds.toTypedArray())
        }
        if (missingCurrentNodeIds.isNotEmpty()) {
            removeMissingCurrentIndexMembers(lockName, currentIndexKey, missingCurrentNodeIds)
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
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        val key = candidateKey(lockName, nodeId)
        if (cmds.get(key) == null) {
            migrateLegacyCandidate(lockName, nodeId)
        }
        val reply = RedisScriptRunner.runSuspending<List<Any>>(
            asyncCommands,
            LettuceCandidateResultScript.UPDATE,
            ScriptOutputType.MULTI,
            arrayOf(key),
            *LettuceCandidateResultScript.resultArgs(result, Instant.now().toEpochMilli()),
        )
        LettuceCandidateResultScript.rethrowMalformed(reply)
    }

    private suspend fun readLegacyNodeIds(lockName: String): Set<String> = try {
        cmds.smembers(LettuceCandidateKeyCodec.legacyIndexKey(keyPrefix, lockName)).toList().toSet()
    } catch (e: RedisCommandExecutionException) {
        if (e.isWrongType()) emptySet() else throw e
    }

    private suspend fun readLegacyCandidate(key: String, expectedNodeId: String): CandidateInfo? {
        val raw = try {
            cmds.get(key)
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
            null
        }
        return raw?.let(LettuceCandidateInfoCodec::decode)?.takeIf { it.nodeId == expectedNodeId }
    }

    private suspend fun readCurrentCandidate(lockName: String, expectedNodeId: String): CandidateInfo? {
        val raw = cmds.get(candidateKey(lockName, expectedNodeId)) ?: return null
        return LettuceCandidateInfoCodec.decode(raw).takeIf { it.nodeId == expectedNodeId }
    }

    private suspend fun migrateLegacyCandidate(lockName: String, nodeId: String): Boolean {
        val legacyKey = LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId)
        val candidate = readLegacyCandidate(legacyKey, nodeId) ?: return false
        val raw = LettuceCandidateInfoCodec.encode(candidate)
        val ttl = cmds.pttl(legacyKey) ?: -2L
        val migrated = when {
            ttl == -2L -> false
            ttl == -1L -> cmds.setnx(candidateKey(lockName, nodeId), raw) == true
            ttl > 0L -> cmds.set(candidateKey(lockName, nodeId), raw, SetArgs.Builder.nx().px(ttl)) != null
            else -> false
        }
        if (migrated) {
            cmds.sadd(indexKey(lockName), nodeId)
            cmds.persist(indexKey(lockName))
        }
        return migrated
    }

    private suspend fun removeMissingCurrentIndexMembers(
        lockName: String,
        indexKey: String,
        nodeIds: List<String>,
    ) {
        val keys = arrayOf(indexKey) + nodeIds.map { candidateKey(lockName, it) }
        RedisScriptRunner.runSuspending<Long>(
            asyncCommands,
            LettuceCandidateIndexCleanupScript.REMOVE_MISSING,
            ScriptOutputType.INTEGER,
            keys,
            *nodeIds.toTypedArray(),
        )
    }

    private suspend fun removeLegacyIndexMembers(lockName: String, nodeIds: List<String>) {
        try {
            cmds.srem(LettuceCandidateKeyCodec.legacyIndexKey(keyPrefix, lockName), *nodeIds.toTypedArray())
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
        }
    }
}

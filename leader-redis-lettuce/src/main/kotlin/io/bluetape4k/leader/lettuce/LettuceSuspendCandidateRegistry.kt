@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.validateLockName
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.coroutines
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * `LettuceSuspendCandidateRegistry`는 suspend Lettuce strategic 후보의 lifecycle과
 * legacy migration을 blocking registry와 동일한 v3 경계로 수행합니다.
 */
@Suppress("TooManyFunctions")
internal class LettuceSuspendCandidateRegistry private constructor(
    private val commands: SuspendCandidateCommands,
    private val readMany: SuspendCandidateValueReader,
    private val keyPrefix: String,
) {

    /** 기존 standalone internal JVM constructor descriptor를 보존합니다. */
    internal constructor(
        connection: StatefulRedisConnection<String, String>,
        keyPrefix: String = DEFAULT_KEY_PREFIX,
    ) : this(
        LettuceSuspendCandidateCommands(connection.coroutines()) { connection.async() },
        StandaloneSuspendCandidateValueReader(connection.coroutines()),
        keyPrefix,
    )

    /** 기존 one-argument internal 호출 surface를 보존합니다. */
    internal constructor(connection: StatefulRedisConnection<String, String>) : this(
        connection,
        DEFAULT_KEY_PREFIX,
    )

    /** Redis Cluster connection을 위한 additive internal constructor입니다. */
    internal constructor(
        connection: StatefulRedisClusterConnection<String, String>,
        keyPrefix: String = DEFAULT_KEY_PREFIX,
    ) : this(
        LettuceSuspendCandidateCommands(connection.coroutines()) { connection.async() },
        ClusterSuspendCandidateValueReader(connection.coroutines()),
        keyPrefix,
    )

    companion object {
        internal const val DEFAULT_KEY_PREFIX = "leader:strategy:candidates"
        internal const val GROUP_KEY_PREFIX = "leader:strategy:group-candidates:lettuce:v1"
    }

    private fun indexKey(lockName: String): String =
        LettuceCandidateKeyCodec.indexKey(keyPrefix, lockName)

    private fun candidateKey(lockName: String, nodeId: String): String =
        LettuceCandidateKeyCodec.candidateKey(keyPrefix, lockName, nodeId)

    private fun tombstoneKey(lockName: String, nodeId: String): String =
        LettuceCandidateKeyCodec.tombstoneKey(keyPrefix, lockName, nodeId)

    private fun migrationTokenKey(lockName: String, nodeId: String): String =
        LettuceCandidateKeyCodec.migrationTokenKey(keyPrefix, lockName, nodeId)

    private fun v2IndexKey(lockName: String): String =
        LettuceCandidateKeyCodec.v2IndexKey(keyPrefix, lockName)

    private fun v2CandidateKey(lockName: String, nodeId: String): String =
        LettuceCandidateKeyCodec.v2CandidateKey(keyPrefix, lockName, nodeId)

    private fun legacyIndexKey(lockName: String): String =
        LettuceCandidateKeyCodec.legacyIndexKey(keyPrefix, lockName)

    private fun legacyCandidateKey(lockName: String, nodeId: String): String =
        LettuceCandidateKeyCodec.legacyCandidateKey(keyPrefix, lockName, nodeId)

    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val ttlMillis = candidateTtlMillis(ttl)
        val reply = runWriteScript(
            operation = LettuceCandidateWriteScript.REGISTER,
            keys = arrayOf(
                candidateKey(lockName, info.nodeId),
                indexKey(lockName),
                tombstoneKey(lockName, info.nodeId),
                migrationTokenKey(lockName, info.nodeId),
            ),
            args = arrayOf(
                LettuceCandidateInfoCodec.encode(info),
                ttlMillis.toString(),
                info.nodeId,
            ),
        )
        requireStatus(reply, LettuceCandidateWriteScript.REGISTERED)
    }

    suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        validateLockName(lockName)
        val ttlMillis = candidateTtlMillis(ttl)
        if (commands.get(tombstoneKey(lockName, info.nodeId)) != null) return
        ensureCurrentCandidate(lockName, info.nodeId)
        val reply = commands.runScript<List<Any>>(
            LettuceCandidateRefreshScript.REFRESH,
            ScriptOutputType.MULTI,
            arrayOf(
                candidateKey(lockName, info.nodeId),
                indexKey(lockName),
                migrationTokenKey(lockName, info.nodeId),
            ),
            LettuceCandidateInfoCodec.encode(info),
            ttlMillis.toString(),
        )
        LettuceCandidateRefreshScript.rethrowMalformed(reply)
    }

    suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        validateLockName(lockName)
        val reply = runWriteScript(
            operation = LettuceCandidateWriteScript.UNREGISTER,
            keys = arrayOf(
                candidateKey(lockName, nodeId),
                indexKey(lockName),
                tombstoneKey(lockName, nodeId),
                migrationTokenKey(lockName, nodeId),
            ),
            args = arrayOf(nodeId),
        )
        requireStatus(reply, LettuceCandidateWriteScript.UNREGISTERED)

        cleanupLegacyCandidate(nodeId, v2CandidateKey(lockName, nodeId), v2IndexKey(lockName))
        cleanupLegacyCandidate(nodeId, legacyCandidateKey(lockName, nodeId), legacyIndexKey(lockName))
    }

    @Suppress("CyclomaticComplexMethod")
    suspend fun listCandidates(lockName: String): List<CandidateInfo> {
        validateLockName(lockName)
        val currentIndex = indexKey(lockName)
        val currentNodeIds = commands.smembers(currentIndex).toList()
        val candidates = linkedMapOf<String, CandidateInfo>()
        val missingCurrentNodeIds = mutableListOf<String>()
        val mismatchedCurrentNodeIds = mutableListOf<String>()

        if (currentNodeIds.isNotEmpty()) {
            val keys = currentNodeIds.map { candidateKey(lockName, it) }
            val values = readMany.read(keys)
            currentNodeIds.forEach { nodeId ->
                if (commands.get(tombstoneKey(lockName, nodeId)) != null) {
                    mismatchedCurrentNodeIds += nodeId
                    return@forEach
                }
                val raw = values[candidateKey(lockName, nodeId)]
                if (raw == null) {
                    missingCurrentNodeIds += nodeId
                    return@forEach
                }
                val candidate = LettuceCandidateInfoCodec.decode(raw)
                if (candidate.nodeId == nodeId) candidates[nodeId] = candidate
                else mismatchedCurrentNodeIds += nodeId
            }
        }

        val v2Index = v2IndexKey(lockName)
        val legacyIndex = legacyIndexKey(lockName)
        val v2NodeIds = readIndexMembers(v2Index)
        val legacyNodeIds = readIndexMembers(legacyIndex)
        val sourceNodeIds = (v2NodeIds + legacyNodeIds).distinct()
        val legacySources = sourceNodeIds.associateWith { nodeId ->
            readLegacySources(lockName, nodeId, nodeId in v2NodeIds, nodeId in legacyNodeIds)
        }
        sourceNodeIds.forEach { nodeId ->
            if (candidates.containsKey(nodeId)) return@forEach
            if (commands.get(tombstoneKey(lockName, nodeId)) != null) return@forEach

            val source = legacySources[nodeId]?.firstOrNull() ?: return@forEach

            if (!migrateLegacyCandidate(lockName, nodeId, source)) return@forEach
            mismatchedCurrentNodeIds.remove(nodeId)
            val currentRaw = commands.get(candidateKey(lockName, nodeId)) ?: return@forEach
            val currentCandidate = LettuceCandidateInfoCodec.decode(currentRaw)
            if (currentCandidate.nodeId == nodeId) candidates[nodeId] = currentCandidate
        }

        if (mismatchedCurrentNodeIds.isNotEmpty()) {
            commands.srem(currentIndex, mismatchedCurrentNodeIds.toTypedArray())
        }
        if (missingCurrentNodeIds.isNotEmpty()) {
            removeMissingCurrentIndexMembers(lockName, currentIndex, missingCurrentNodeIds)
        }
        return candidates.values.toList()
    }

    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        validateLockName(lockName)
        if (commands.get(tombstoneKey(lockName, nodeId)) != null) return
        ensureCurrentCandidate(lockName, nodeId)
        val reply = commands.runScript<List<Any>>(
            LettuceCandidateResultScript.UPDATE,
            ScriptOutputType.MULTI,
            arrayOf(candidateKey(lockName, nodeId), migrationTokenKey(lockName, nodeId)),
            *LettuceCandidateResultScript.resultArgs(result, Instant.now().toEpochMilli()),
        )
        LettuceCandidateResultScript.rethrowMalformed(reply)
    }

    private suspend fun runWriteScript(
        operation: String,
        keys: Array<String>,
        args: Array<String>,
    ): List<Any> = commands.runScript(
        LettuceCandidateWriteScript.WRITE,
        ScriptOutputType.MULTI,
        keys,
        operation,
        *args,
    )

    private fun requireStatus(reply: List<Any>, expected: Long) {
        val actual = reply.firstOrNull()?.toString()?.toLongOrNull()
        require(actual == expected) {
            "Unexpected candidate write status: expected=$expected actual=$actual"
        }
    }

    private suspend fun ensureCurrentCandidate(lockName: String, nodeId: String): Boolean {
        if (commands.get(candidateKey(lockName, nodeId)) != null) return true
        val source = readSourceCandidate(lockName, nodeId)
        return if (source == null) {
            false
        } else {
            migrateLegacyCandidate(lockName, nodeId, source)
            commands.get(candidateKey(lockName, nodeId)) != null
        }
    }

    private suspend fun readIndexMembers(indexKey: String): Set<String> = try {
        commands.smembers(indexKey)
    } catch (e: RedisCommandExecutionException) {
        if (e.isWrongType()) emptySet() else throw e
    }

    private suspend fun readSourceCandidate(lockName: String, nodeId: String): LegacyCandidate? {
        val v2Key = v2CandidateKey(lockName, nodeId)
        val v2Candidate = readLegacyCandidate(v2Key, nodeId)
        val legacyKey = legacyCandidateKey(lockName, nodeId)
        val legacyCandidate = readLegacyCandidate(legacyKey, nodeId)
        return v2Candidate?.let { LegacyCandidate(v2Key, v2IndexKey(lockName)) }
            ?: legacyCandidate?.let { LegacyCandidate(legacyKey, legacyIndexKey(lockName)) }
    }

    private suspend fun readLegacySources(
        lockName: String,
        nodeId: String,
        v2Indexed: Boolean,
        legacyIndexed: Boolean,
    ): List<LegacyCandidate> = buildList {
        addLegacySourceIfValid(
            nodeId,
            v2CandidateKey(lockName, nodeId),
            v2IndexKey(lockName),
            v2Indexed,
        )?.let(::add)
        addLegacySourceIfValid(
            nodeId,
            legacyCandidateKey(lockName, nodeId),
            legacyIndexKey(lockName),
            legacyIndexed,
        )?.let(::add)
    }

    private suspend fun addLegacySourceIfValid(
        nodeId: String,
        sourceKey: String,
        sourceIndexKey: String,
        indexed: Boolean,
    ): LegacyCandidate? {
        val candidate = readLegacyCandidate(sourceKey, nodeId)
        return if (candidate == null) {
            if (indexed) removeLegacyIndexMembers(sourceIndexKey, listOf(nodeId))
            null
        } else {
            LegacyCandidate(sourceKey, sourceIndexKey)
        }
    }

    private suspend fun readLegacyCandidate(key: String, expectedNodeId: String): CandidateInfo? {
        val raw = try {
            commands.get(key)
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
            null
        }
        return raw?.let(LettuceCandidateInfoCodec::decode)?.takeIf { it.nodeId == expectedNodeId }
    }

    private suspend fun migrateLegacyCandidate(lockName: String, nodeId: String, source: LegacyCandidate): Boolean {
        val sourceRaw = commands.get(source.key)
        val sourceCandidate = sourceRaw?.let(LettuceCandidateInfoCodec::decode)
        return if (sourceRaw == null || sourceCandidate?.nodeId != nodeId) {
            removeSourceIndexMember(source, nodeId)
            false
        } else {
            val observedTtl = commands.pttl(source.key)
            if (observedTtl == REDIS_KEY_ABSENT_TTL || (observedTtl <= 0L && observedTtl != -1L)) {
                removeSourceIndexMember(source, nodeId)
                false
            } else {
                val token = UUID.randomUUID().toString()
                val reply = runWriteScript(
                    operation = LettuceCandidateWriteScript.MIGRATE,
                    keys = arrayOf(
                        candidateKey(lockName, nodeId),
                        indexKey(lockName),
                        tombstoneKey(lockName, nodeId),
                        migrationTokenKey(lockName, nodeId),
                    ),
                    args = arrayOf(sourceRaw, observedTtl.toString(), nodeId, token),
                )
                when (reply.firstOrNull()?.toString()?.toLongOrNull()) {
                    LettuceCandidateWriteScript.MIGRATED -> {
                        cleanupExpiredMigration(lockName, nodeId, source.key, sourceRaw, observedTtl, token)
                        commands.get(candidateKey(lockName, nodeId)) != null
                    }
                    LettuceCandidateWriteScript.EXISTING_REPAIRED -> true
                    LettuceCandidateWriteScript.MALFORMED -> {
                        LettuceCandidateInfoCodec.decode(reply.getOrNull(1)?.toString().orEmpty())
                        false
                    }
                    else -> false
                }
            }
        }
    }

    private suspend fun cleanupExpiredMigration(
        lockName: String,
        nodeId: String,
        sourceKey: String,
        sourceRaw: String,
        observedTtl: Long,
        token: String,
    ) {
        val sourceAfter = commands.get(sourceKey)
        val sourceTtlAfter = commands.pttl(sourceKey)
        val expired = sourceAfter == null ||
            sourceTtlAfter == REDIS_KEY_ABSENT_TTL ||
            (observedTtl > 0L && sourceTtlAfter == 0L)
        if (!expired) return

        runWriteScript(
            operation = LettuceCandidateWriteScript.REMOVE_IF_VALUE,
            keys = arrayOf(
                candidateKey(lockName, nodeId),
                indexKey(lockName),
                migrationTokenKey(lockName, nodeId),
            ),
            args = arrayOf(sourceRaw, token, nodeId),
        )
    }

    private suspend fun removeMissingCurrentIndexMembers(
        lockName: String,
        currentIndex: String,
        nodeIds: List<String>,
    ) {
        val keys = buildList {
            add(currentIndex)
            nodeIds.forEach { nodeId ->
                add(candidateKey(lockName, nodeId))
                add(migrationTokenKey(lockName, nodeId))
            }
        }.toTypedArray()
        commands.runScript<Long>(
            LettuceCandidateIndexCleanupScript.REMOVE_MISSING,
            ScriptOutputType.INTEGER,
            keys,
            *nodeIds.toTypedArray(),
        )
    }

    private suspend fun cleanupLegacyCandidate(nodeId: String, candidateKey: String, indexKey: String) {
        if (readLegacyCandidate(candidateKey, nodeId) != null) commands.del(candidateKey)
        removeLegacyIndexMembers(indexKey, listOf(nodeId))
    }

    private suspend fun removeLegacyIndexMembers(indexKey: String, nodeIds: List<String>) {
        try {
            commands.srem(indexKey, nodeIds.toTypedArray())
        } catch (e: RedisCommandExecutionException) {
            if (!e.isWrongType()) throw e
        }
    }

    private suspend fun removeSourceIndexMember(source: LegacyCandidate, nodeId: String) {
        removeLegacyIndexMembers(source.indexKey, listOf(nodeId))
    }

    private fun candidateTtlMillis(ttl: Duration): Long {
        require(ttl >= Duration.ZERO) { "Candidate TTL must be non-negative" }
        val millis = ttl.inWholeMilliseconds
        require(ttl == Duration.ZERO || millis > 0L) {
            "Candidate TTL must be zero or at least 1ms"
        }
        return millis
    }

    private data class LegacyCandidate(
        val key: String,
        val indexKey: String,
    )
}

private const val REDIS_KEY_ABSENT_TTL = -2L

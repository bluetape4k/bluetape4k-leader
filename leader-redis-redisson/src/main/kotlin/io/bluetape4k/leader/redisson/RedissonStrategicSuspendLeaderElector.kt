package io.bluetape4k.leader.redisson

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [StrategicSuspendLeaderElector] implementation backed by the Redisson Redis client.
 *
 * Redisson blocking calls are executed on [Dispatchers.IO].
 * [CancellationException] is not treated as an action failure; it is re-thrown without incrementing failureCount.
 *
 * @param redissonClient Redisson client
 * @param nodeId Node identifier for this instance. Auto-generated as UUID v7 if not specified.
 */
class RedissonStrategicSuspendLeaderElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderElector {

    companion object : KLogging()

    private val registry = RedissonCandidateRegistry(redissonClient)

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        withContext(Dispatchers.IO) { registry.registerCandidate(lockName, info, ttl) }

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        withContext(Dispatchers.IO) { registry.unregisterCandidate(lockName, nodeId) }

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        withContext(Dispatchers.IO) { registry.listCandidates(lockName) }

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        withContext(Dispatchers.IO) { registry.updateResult(lockName, nodeId, result) }

    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
    ): T? {
        val candidates = try {
            listCandidates(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "[$lockName] 후보 목록 조회 실패 — 선출 skip" }
            return null
        }
        val result = strategy.elect(candidates)
        val winner = result.winner ?: return null

        val total = result.eliminations.size + 1
        log.info { "[$lockName] 선출: ${winner.nodeId} (전략: ${strategy::class.simpleName}, 후보: ${total}명)" }
        if (result.scores.isNotEmpty()) {
            val scoreText = result.scores.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { (id, s) -> "$id=%.2f".format(s) }
            log.debug { "[$lockName] 점수: $scoreText" }
        }
        result.eliminations.forEach { e ->
            log.debug { "[$lockName] 탈락: ${e.candidate.nodeId} — ${e.reason}" }
        }

        if (winner.nodeId != nodeId) return null

        return try {
            val value = action()
            runCatching { updateResult(lockName, nodeId, CandidateResult.SUCCESS) }
                .onFailure { log.warn(it) { "[$lockName] successCount 업데이트 실패 — 무시됨" } }
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { updateResult(lockName, nodeId, CandidateResult.FAILURE) }
                .onFailure { log.warn(it) { "[$lockName] failureCount 업데이트 실패 — 무시됨" } }
            throw e
        }
    }
}

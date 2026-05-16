package io.bluetape4k.leader.redisson

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [StrategicLeaderElector] implementation backed by Redisson.
 *
 * ## Election approach
 * Uses a deterministic strategy without distributed locks.
 * When all nodes apply the same strategy to the same candidate list, they compute the same winner.
 * Only the winner node executes the action.
 *
 * ## Note
 * Differences in candidate registration/expiry timing may cause nodes to see different candidate lists.
 * Use [RedissonLeaderElector] (lock-based) when strict mutual exclusion is required.
 *
 * @param redissonClient Redisson client
 * @param nodeId node identifier for this instance; auto-generated as UUID v7 when not specified
 */
class RedissonStrategicLeaderElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicLeaderElector {

    companion object : KLogging()

    private val registry = RedissonCandidateRegistry(redissonClient)

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        val candidates = runCatching { listCandidates(lockName) }
            .onFailure { log.warn(it) { "[$lockName] 후보 목록 조회 실패 — 선출 skip" } }
            .getOrElse { return null }
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

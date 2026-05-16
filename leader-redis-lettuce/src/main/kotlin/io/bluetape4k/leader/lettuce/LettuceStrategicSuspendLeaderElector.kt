@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

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
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [StrategicSuspendLeaderElector] implementation backed by the Lettuce Redis client.
 *
 * Delegates to [LettuceSuspendCandidateRegistry] to execute Redis commands via the Lettuce coroutines API.
 * Because Lettuce uses Netty-based asynchronous I/O, no [kotlinx.coroutines.Dispatchers.IO] switch is required.
 *
 * [CancellationException] is not treated as an action failure; it is re-thrown without incrementing failureCount.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec-based)
 * @param nodeId Node identifier for this instance. Auto-generated as UUID v7 if not specified.
 */
class LettuceStrategicSuspendLeaderElector(
    connection: StatefulRedisConnection<String, String>,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderElector {

    companion object : KLogging()

    private val registry = LettuceSuspendCandidateRegistry(connection)

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    /**
     * Elects a leader using the given strategy and executes [action] only if this node is the winner.
     *
     * Because deterministic election is used without a distributed lock, the waitTime/leaseTime in [options] are not applied.
     * Set the TTL directly when registering candidates.
     *
     * [CancellationException] is not treated as a failure and does not increment failureCount.
     *
     * @return The result of [action] if this node is elected, or `null` if there are no candidates or another node wins.
     */
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

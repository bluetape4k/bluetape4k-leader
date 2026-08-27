package io.bluetape4k.leader.redisson

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.redisson.api.RedissonClient
import kotlin.time.Duration

/**
 * `RedissonStrategicSuspendLeaderGroupElector`는 Redis Redisson 후보 기준 목록에서
 * 결정론적인 top-N leader를 선택하는 coroutine API입니다.
 *
 * `maxLeaders`는 관찰한 후보 기준 목록의 선택 수이며 전역 동시 실행 상한이 아닙니다.
 */
class RedissonStrategicSuspendLeaderGroupElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderGroupElector {

    companion object : KLogging()

    private val registry = RedissonCandidateRegistry(
        redissonClient,
        RedissonCandidateRegistry.GROUP_KEY_PREFIX,
    )

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidateSuspending(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidateSuspending(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidatesSuspending(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResultSuspending(lockName, nodeId, result)

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: suspend () -> T,
    ): T? {
        validateLockName(lockName)
        currentCoroutineContext().ensureActive()
        val candidates = try {
            listCandidates(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[$lockName] 후보 목록 조회 실패 — 전략적 그룹 선출 중단" }
            throw e
        }
        val result = strategy.electValidated(candidates, maxLeaders)
        if (result.winners.isEmpty()) return null

        log.info {
            "[$lockName] 전략적 그룹 선출: ${result.winners.joinToString { it.nodeId }} " +
                "(전략: ${strategy::class.simpleName}, 후보: ${result.winners.size + result.eliminations.size}명)"
        }
        if (result.scores.isNotEmpty()) {
            log.debug {
                val scoreText = result.scores.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { (id, score) -> "$id=%.2f".format(score) }
                "[$lockName] 점수: $scoreText"
            }
        }
        result.eliminations.forEach { elimination ->
            log.debug { "[$lockName] 탈락: ${elimination.candidate.nodeId} — ${elimination.reason}" }
        }

        if (result.winners.none { it.nodeId == nodeId }) return null

        return try {
            val value = action()
            currentCoroutineContext().ensureActive()
            updateStrategicResultPreservingCancellation(
                lockName = lockName,
                result = CandidateResult.SUCCESS,
                update = { updateResult(lockName, nodeId, CandidateResult.SUCCESS) },
                onFailure = { exception, message -> log.warn(exception) { message } },
            )
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateStrategicResultPreservingCancellation(
                lockName = lockName,
                result = CandidateResult.FAILURE,
                update = { updateResult(lockName, nodeId, CandidateResult.FAILURE) },
                onFailure = { exception, message -> log.warn(exception) { message } },
            )
            throw e
        }
    }
}

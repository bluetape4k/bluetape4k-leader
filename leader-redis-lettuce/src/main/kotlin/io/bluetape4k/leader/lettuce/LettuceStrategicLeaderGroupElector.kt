package io.bluetape4k.leader.lettuce

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.StrategicLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * `LettuceStrategicLeaderGroupElector`는 Redis Lettuce 후보 기준 목록에서
 * 결정론적인 top-N leader를 선택하는 blocking API입니다.
 *
 * `maxLeaders`는 관찰한 후보 기준 목록의 선택 수이며 전역 동시 실행 상한이 아닙니다.
 */
class LettuceStrategicLeaderGroupElector(
    connection: StatefulRedisConnection<String, String>,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicLeaderGroupElector {

    companion object : KLogging()

    private val registry = LettuceCandidateRegistry(
        connection,
        LettuceCandidateRegistry.GROUP_KEY_PREFIX,
    )

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: () -> T,
    ): T? {
        validateLockName(lockName)
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

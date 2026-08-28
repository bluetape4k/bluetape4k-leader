package io.bluetape4k.leader.lettuce

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `LettuceStrategicLeaderElector`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property nodeId Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceStrategicLeaderElector(
    connection: StatefulRedisConnection<String, String>,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicLeaderElector {

    companion object : KLogging()

    private val registry = LettuceCandidateRegistry(connection)

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.refreshCandidate(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    /**
     * `선언` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        // 정책 위반은 정상 contention이 아니므로 후보 조회 실패 fallback보다 먼저 전파합니다.
        validateLockName(lockName)
        val candidates = try {
            listCandidates(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[$lockName] 후보 목록 조회 실패 — 선출 중단" }
            throw e
        }
        val result = strategy.elect(candidates)
        val winner = result.winner ?: return null

        val total = result.eliminations.size + 1
        log.info { "[$lockName] 선출: ${winner.nodeId} (전략: ${strategy::class.simpleName}, 후보: ${total}명)" }
        if (result.scores.isNotEmpty()) {
            log.debug {
                val scoreText = result.scores.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { (id, s) -> "$id=%.2f".format(s) }
                "[$lockName] 점수: $scoreText"
            }
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

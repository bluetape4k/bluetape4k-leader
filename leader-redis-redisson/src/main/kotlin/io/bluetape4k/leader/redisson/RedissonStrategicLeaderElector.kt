package io.bluetape4k.leader.redisson

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
import kotlinx.coroutines.CancellationException
import org.redisson.api.RedissonClient
import kotlin.time.Duration

/**
 * `RedissonStrategicLeaderElector`는 Redis Redisson 후보 registry와 전략 점수를 사용해 단일 leader 후보를 선택하고 lease를 실행합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property nodeId 후보 registry에서 이 인스턴스를 식별할 값입니다. 기본값은 `Uuid.V7.nextBase62()`로 생성합니다.
 */
class RedissonStrategicLeaderElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
): StrategicLeaderElector {

    companion object: KLogging()

    private val registry = RedissonCandidateRegistry(redissonClient)

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

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        lockName.validateLockName()
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
        result.eliminations.forEach { log.debug { "[$lockName] 탈락: ${it.candidate.nodeId} — ${it.reason}" } }

        if (winner.nodeId != nodeId) return null

        return try {
            val value = action()
            runCatching {
                updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            }.onFailure {
                log.warn(it) { "[$lockName] successCount 업데이트 실패 — 무시됨" }
            }
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching {
                updateResult(lockName, nodeId, CandidateResult.FAILURE)
            }.onFailure {
                log.warn(it) { "[$lockName] failureCount 업데이트 실패 — 무시됨" }
            }
            throw e
        }
    }
}

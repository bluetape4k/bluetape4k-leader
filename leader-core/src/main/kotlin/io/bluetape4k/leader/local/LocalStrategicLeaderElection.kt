package io.bluetape4k.leader.local

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 인메모리(단일 프로세스) [StrategicLeaderElection] 구현체입니다.
 *
 * 단일 프로세스 내 pilot 테스트 및 단위 테스트 용도로 사용합니다.
 * lockName 단위 [ReentrantLock] 으로 선출 단계의 스레드 안전성을 보장합니다.
 * action 실행은 락 외부에서 수행하여 무관한 lockName 간 간섭을 방지합니다.
 *
 * @property nodeId 이 인스턴스가 나타내는 노드 식별자. 미지정 시 UUID v7([Uuid.V7])로 자동 생성됩니다.
 */
class LocalStrategicLeaderElection(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
) : StrategicLeaderElection {

    companion object : KLogging()

    private val registry = ConcurrentHashMap<String, ConcurrentHashMap<String, CandidateInfo>>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun candidatesFor(lockName: String): ConcurrentHashMap<String, CandidateInfo> =
        registry.getOrPut(lockName) { ConcurrentHashMap() }

    private fun lockFor(lockName: String): ReentrantLock =
        locks.getOrPut(lockName) { ReentrantLock() }

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName)[info.nodeId] = info
    }

    override fun unregisterCandidate(lockName: String, nodeId: String) {
        candidatesFor(lockName).remove(nodeId)
    }

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        candidatesFor(lockName).values.toList()

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidatesFor(lockName).computeIfPresent(nodeId) { _, current -> current.withResult(result) }
    }

    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        // 선출 단계만 lockName 단위 락으로 보호
        val result = lockFor(lockName).withLock {
            strategy.elect(listCandidates(lockName))
        }
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

package io.bluetape4k.leader.local

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap

/**
 * 인메모리(단일 프로세스) [StrategicSuspendLeaderElector] 구현체입니다.
 *
 * [LocalStrategicLeaderElector] 의 suspend 버전으로, lockName 단위 [Mutex] 로 코루틴 안전성을 보장합니다.
 * action 실행은 뮤텍스 외부에서 수행하여 무관한 lockName 간 간섭을 방지합니다.
 * [CancellationException] 은 작업 실패로 간주하지 않으며, failureCount 를 증가시키지 않고 즉시 재전파합니다.
 *
 * @property nodeId 이 인스턴스가 나타내는 노드 식별자. 미지정 시 UUID v7([Uuid.V7])로 자동 생성됩니다.
 */
class LocalStrategicSuspendLeaderElector(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
) : StrategicSuspendLeaderElector {

    companion object : KLogging()

    private val registry = ConcurrentHashMap<String, ConcurrentHashMap<String, CandidateInfo>>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun candidatesFor(lockName: String): ConcurrentHashMap<String, CandidateInfo> =
        registry.computeIfAbsent(lockName) { ConcurrentHashMap() }

    private fun mutexFor(lockName: String): Mutex =
        mutexes.computeIfAbsent(lockName) { Mutex() }

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName)[info.nodeId] = info
    }

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        candidatesFor(lockName).remove(nodeId)
    }

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        candidatesFor(lockName).values.toList()

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidatesFor(lockName).computeIfPresent(nodeId) { _, current -> current.withResult(result) }
    }

    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
    ): T? {
        // 선출 단계만 lockName 단위 뮤텍스로 보호
        val result = mutexFor(lockName).withLock {
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

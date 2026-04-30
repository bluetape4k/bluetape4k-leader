package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 인메모리(단일 프로세스) [StrategicSuspendLeaderElection] 구현체입니다.
 *
 * [LocalStrategicLeaderElection] 의 suspend 버전으로, lockName 단위 [Mutex] 로 코루틴 안전성을 보장합니다.
 * action 실행은 뮤텍스 외부에서 수행하여 무관한 lockName 간 간섭을 방지합니다.
 * [CancellationException] 은 작업 실패로 간주하지 않으며, failureCount 를 증가시키지 않고 즉시 재전파합니다.
 *
 * @property nodeId 이 인스턴스가 나타내는 노드 식별자
 */
class LocalStrategicSuspendLeaderElection(override val nodeId: String) : StrategicSuspendLeaderElection {

    private val registry = ConcurrentHashMap<String, ConcurrentHashMap<String, CandidateInfo>>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun candidatesFor(lockName: String): ConcurrentHashMap<String, CandidateInfo> =
        registry.getOrPut(lockName) { ConcurrentHashMap() }

    private fun mutexFor(lockName: String): Mutex =
        mutexes.getOrPut(lockName) { Mutex() }

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
        val winner = mutexFor(lockName).withLock {
            strategy.selectLeader(listCandidates(lockName))
        } ?: return null
        if (winner.nodeId != nodeId) return null

        // action 은 뮤텍스 외부에서 실행
        return try {
            val value = action()
            updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            value
        } catch (e: CancellationException) {
            // 코루틴 취소는 작업 실패가 아님 — failureCount 증가 없이 재전파
            throw e
        } catch (e: Throwable) {
            updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

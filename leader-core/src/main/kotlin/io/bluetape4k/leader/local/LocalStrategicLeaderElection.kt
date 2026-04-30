package io.bluetape4k.leader.local

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
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
 * @property nodeId 이 인스턴스가 나타내는 노드 식별자. 미지정 시 UUID v7([TimebasedUuid.Epoch])로 자동 생성됩니다.
 */
class LocalStrategicLeaderElection(
    override val nodeId: String = TimebasedUuid.Epoch.nextIdAsString(),
) : StrategicLeaderElection {

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
        val winner = lockFor(lockName).withLock {
            strategy.selectLeader(listCandidates(lockName))
        } ?: return null
        if (winner.nodeId != nodeId) return null

        // action 은 락 외부에서 실행
        return try {
            val value = action()
            updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            value
        } catch (e: Throwable) {
            updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

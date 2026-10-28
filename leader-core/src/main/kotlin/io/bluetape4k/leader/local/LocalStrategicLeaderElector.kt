package io.bluetape4k.leader.local

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * JVM 메모리에 후보를 보관하고 `ElectionStrategy`로 단일 leader를 선택하는 blocking elector입니다.
 * 양수 `ttl`은 후보 만료에 적용되며, `Duration.ZERO`는 만료되지 않는 후보를 뜻합니다.
 *
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 */
class LocalStrategicLeaderElector(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
): StrategicLeaderElector {

    companion object: KLogging()

    private val registry = ConcurrentHashMap<String, LocalStrategicCandidateRegistry>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun candidatesFor(lockName: String): LocalStrategicCandidateRegistry {
        lockName.validateLockName()
        return registry.computeIfAbsent(lockName) { LocalStrategicCandidateRegistry() }
    }

    private fun lockFor(lockName: String): ReentrantLock =
        locks.computeIfAbsent(lockName) { ReentrantLock() }

    /**
     * 후보를 현재 인스턴스의 메모리에 등록하고 TTL을 설정합니다.
     * 양수 TTL은 후보를 만료시키며, 0 이하 또는 [Duration.INFINITE]는 만료되지 않습니다.
     * @param lockName 후보 집합을 구분하는 lock 이름
     * @param info 메모리에 등록할 후보 snapshot
     * @param ttl 후보가 유효한 기간입니다.
     */
    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName).register(info, ttl)
    }

    /**
     * 기존 후보의 metadata와 TTL을 갱신합니다. 없는 후보는 무시하고, 이미 만료된 후보는 제거합니다.
     * @param lockName 후보 집합을 구분하는 lock 이름
     * @param info 기존 후보와 교체할 metadata를 담은 정보
     * @param ttl 갱신할 후보의 유효 기간입니다.
     */
    override fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName).refresh(info, ttl)
    }

    override fun unregisterCandidate(lockName: String, nodeId: String) {
        candidatesFor(lockName).unregister(nodeId)
    }

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        candidatesFor(lockName).list()

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidatesFor(lockName).updateResult(nodeId, result)
    }

    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        log.debug { "runIfLeader... lockName=$lockName" }

        lockName.validateLockName()
        // 선출 단계만 lockName 단위 락으로 보호
        val result = lockFor(lockName).withLock {
            strategy.elect(listCandidates(lockName))
        }
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

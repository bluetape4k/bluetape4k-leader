package io.bluetape4k.leader.local

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * `LocalStrategicSuspendLeaderGroupElector`는 JVM 메모리 후보 중 여러 leader를 전략적으로 선택하는 suspend elector입니다.
 * 양수 `ttl`은 후보 만료에 적용되며, `Duration.ZERO`는 만료되지 않는 후보를 뜻합니다.
 */
class LocalStrategicSuspendLeaderGroupElector(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
): StrategicSuspendLeaderGroupElector {

    companion object: KLoggingChannel()

    private val registry = ConcurrentHashMap<String, LocalStrategicCandidateRegistry>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun candidatesFor(lockName: String): LocalStrategicCandidateRegistry {
        lockName.validateLockName()
        return registry.computeIfAbsent(lockName) { LocalStrategicCandidateRegistry() }
    }

    private fun mutexFor(lockName: String): Mutex =
        mutexes.computeIfAbsent(lockName) { Mutex() }

    /**
     * 후보를 현재 인스턴스의 메모리에 등록하고 TTL을 설정합니다.
     * 양수 TTL은 후보를 만료시키며, 0 이하 또는 [Duration.INFINITE]는 만료되지 않습니다.
     * @param lockName 후보 집합을 구분하는 lock 이름
     * @param info 메모리에 등록할 후보 snapshot
     * @param ttl 후보가 유효한 기간입니다.
     */
    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName).register(info, ttl)
    }

    /**
     * 기존 후보의 metadata와 TTL을 갱신합니다. 없는 후보는 무시하고, 이미 만료된 후보는 제거합니다.
     * @param lockName 후보 집합을 구분하는 lock 이름
     * @param info 기존 후보와 교체할 metadata를 담은 정보
     * @param ttl 갱신할 후보의 유효 기간입니다.
     */
    override suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName).refresh(info, ttl)
    }

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) {
        candidatesFor(lockName).unregister(nodeId)
    }

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        candidatesFor(lockName).list()

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidatesFor(lockName).updateResult(nodeId, result)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: suspend () -> T,
    ): T? {
        log.debug { "runIfLeader... lockName=$lockName, strategy=$strategy, maxLeaders=$maxLeaders" }

        lockName.validateLockName()
        val result = mutexFor(lockName).withLock {
            val snapshot = listCandidates(lockName)
            strategy.electValidated(snapshot, maxLeaders)
        }
        if (result.winners.isEmpty()) return null

        val total = result.winners.size + result.eliminations.size
        log.info {
            "[$lockName] 전략적 그룹 선출: ${result.winners.joinToString { it.nodeId }} " +
                    "(전략: ${strategy::class.simpleName}, 후보: ${total}명)"
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

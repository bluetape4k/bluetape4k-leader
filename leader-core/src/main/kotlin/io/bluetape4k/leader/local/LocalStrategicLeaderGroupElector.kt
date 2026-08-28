package io.bluetape4k.leader.local

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.StrategicLeaderGroupElector
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
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
 * `LocalStrategicLeaderGroupElector`는 JVM 메모리에서 후보를 등록하고 전략적으로
 * 여러 leader를 선택하는 blocking elector입니다.
 */
class LocalStrategicLeaderGroupElector(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
) : StrategicLeaderGroupElector {

    companion object : KLogging()

    private val registry = ConcurrentHashMap<String, ConcurrentHashMap<String, CandidateInfo>>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun candidatesFor(lockName: String): ConcurrentHashMap<String, CandidateInfo> {
        validateLockName(lockName)
        return registry.computeIfAbsent(lockName) { ConcurrentHashMap() }
    }

    private fun lockFor(lockName: String): ReentrantLock =
        locks.computeIfAbsent(lockName) { ReentrantLock() }

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName)[info.nodeId] = info
    }

    override fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidatesFor(lockName).computeIfPresent(info.nodeId) { _, current ->
            current.copy(metadata = info.metadata)
        }
    }

    override fun unregisterCandidate(lockName: String, nodeId: String) {
        candidatesFor(lockName).remove(nodeId)
    }

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        candidatesFor(lockName).values.toList()

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidatesFor(lockName).computeIfPresent(nodeId) { _, current -> current.withResult(result) }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: () -> T,
    ): T? {
        validateLockName(lockName)
        val result = lockFor(lockName).withLock {
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

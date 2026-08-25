package io.bluetape4k.leader.local

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * `LocalStrategicSuspendLeaderGroupElector`는 JVM 메모리에서 후보를 등록하고
 * 전략적으로 여러 leader를 선택하는 coroutine elector입니다.
 */
class LocalStrategicSuspendLeaderGroupElector(
    override val nodeId: String = Uuid.V7.nextIdAsString(),
) : StrategicSuspendLeaderGroupElector {

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

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: suspend () -> T,
    ): T? {
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

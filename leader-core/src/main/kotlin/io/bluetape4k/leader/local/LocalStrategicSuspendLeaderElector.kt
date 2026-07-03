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
 * In-memory (single-process) [StrategicSuspendLeaderElector] implementation.
 *
 * Suspend variant of [LocalStrategicLeaderElector] — coroutine safety in the election phase is
 * guaranteed by a per-lockName [Mutex]. Action execution is performed outside the mutex to prevent
 * interference across unrelated lockNames. [CancellationException] is not treated as a task failure;
 * it does not increment failureCount and is immediately rethrown.
 *
 * @property nodeId node identifier represented by this instance. Auto-generated as UUID v7 ([Uuid.V7]) if not specified.
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
            updateStrategicResultPreservingCancellation(
                lockName = lockName,
                result = CandidateResult.SUCCESS,
                update = { updateResult(lockName, nodeId, CandidateResult.SUCCESS) },
                onFailure = { e, message -> log.warn(e) { message } },
            )
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateStrategicResultPreservingCancellation(
                lockName = lockName,
                result = CandidateResult.FAILURE,
                update = { updateResult(lockName, nodeId, CandidateResult.FAILURE) },
                onFailure = { e, message -> log.warn(e) { message } },
            )
            throw e
        }
    }
}

internal suspend fun updateStrategicResultPreservingCancellation(
    lockName: String,
    result: CandidateResult,
    update: suspend () -> Unit,
    onFailure: (Exception, String) -> Unit,
) {
    try {
        update()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e, "[$lockName] ${result.logFieldName} 업데이트 실패 — 무시됨")
    }
}

private val CandidateResult.logFieldName: String
    get() = when (this) {
        CandidateResult.SUCCESS -> "successCount"
        CandidateResult.FAILURE -> "failureCount"
    }

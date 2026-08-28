package io.bluetape4k.leader.redisson

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `RedissonStrategicSuspendLeaderElector`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property nodeId Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class RedissonStrategicSuspendLeaderElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderElector {

    companion object : KLogging()

    private val registry = RedissonCandidateRegistry(redissonClient)

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        withContext(Dispatchers.IO) { registry.registerCandidate(lockName, info, ttl) }

    override suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        withContext(Dispatchers.IO) { registry.refreshCandidate(lockName, info, ttl) }

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        withContext(Dispatchers.IO) { registry.unregisterCandidate(lockName, nodeId) }

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        withContext(Dispatchers.IO) { registry.listCandidates(lockName) }

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        withContext(Dispatchers.IO) { registry.updateResult(lockName, nodeId, result) }

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
    ): T? {
        validateLockName(lockName)
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

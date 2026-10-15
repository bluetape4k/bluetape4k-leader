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

/**
 * `RedissonStrategicSuspendLeaderElector`는 Redis Redisson 후보 registry와 전략 점수를 사용해 단일 leader 후보를 선택하고 lease를 실행합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property nodeId 후보 registry에서 이 인스턴스를 식별할 값입니다. 기본값은 `Uuid.V7.nextBase62()`로 생성합니다.
 */
class RedissonStrategicSuspendLeaderElector(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
): StrategicSuspendLeaderElector {

    companion object: KLogging()

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
        lockName.validateLockName()
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
        result.eliminations.forEach { log.debug { "[$lockName] 탈락: ${it.candidate.nodeId} — ${it.reason}" } }

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

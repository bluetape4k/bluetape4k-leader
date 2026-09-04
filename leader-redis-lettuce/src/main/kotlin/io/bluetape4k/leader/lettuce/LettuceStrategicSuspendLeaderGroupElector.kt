@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.electValidated
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration

/**
 * `LettuceStrategicSuspendLeaderGroupElector`는 Redis Lettuce 후보 기준 목록에서
 * 결정론적인 top-N leader를 선택하는 coroutine API입니다.
 *
 * `maxLeaders`는 관찰한 후보 기준 목록의 선택 수이며 전역 동시 실행 상한이 아닙니다.
 */
class LettuceStrategicSuspendLeaderGroupElector private constructor(
    private val registry: LettuceSuspendCandidateRegistry,
    override val nodeId: String,
) : StrategicSuspendLeaderGroupElector {

    @JvmOverloads
    constructor(
        connection: StatefulRedisConnection<String, String>,
        nodeId: String = Uuid.V7.nextBase62(),
    ) : this(
        LettuceSuspendCandidateRegistry(connection, LettuceSuspendCandidateRegistry.GROUP_KEY_PREFIX),
        nodeId,
    )

    @JvmOverloads
    constructor(
        connection: StatefulRedisClusterConnection<String, String>,
        nodeId: String = Uuid.V7.nextBase62(),
    ) : this(
        LettuceSuspendCandidateRegistry(connection, LettuceSuspendCandidateRegistry.GROUP_KEY_PREFIX),
        nodeId,
    )

    companion object : KLogging()

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.refreshCandidate(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount")
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: suspend () -> T,
    ): T? {
        validateLockName(lockName)
        currentCoroutineContext().ensureActive()
        val candidates = try {
            listCandidates(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[$lockName] 후보 목록 조회 실패 — 전략적 그룹 선출 중단" }
            throw e
        }
        val result = strategy.electValidated(candidates, maxLeaders)
        if (result.winners.isEmpty()) return null

        log.info {
            "[$lockName] 전략적 그룹 선출: ${result.winners.joinToString { it.nodeId }} " +
                "(전략: ${strategy::class.simpleName}, 후보: ${result.winners.size + result.eliminations.size}명)"
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
            currentCoroutineContext().ensureActive()
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

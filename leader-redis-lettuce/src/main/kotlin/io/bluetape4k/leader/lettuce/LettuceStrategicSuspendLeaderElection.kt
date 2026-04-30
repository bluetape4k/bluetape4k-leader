@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import java.time.Duration

/**
 * Lettuce 백엔드 기반 [StrategicSuspendLeaderElection] 구현체입니다.
 *
 * [LettuceSuspendCandidateRegistry] 에 위임하여 Lettuce coroutines API 로 Redis 명령을 실행합니다.
 * Lettuce Netty 비동기 I/O 기반이므로 [kotlinx.coroutines.Dispatchers.IO] 전환이 불필요합니다.
 *
 * [CancellationException] 은 작업 실패가 아니므로 failureCount 를 증가시키지 않고 재전파합니다.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 * @param nodeId 이 인스턴스의 노드 식별자. 미지정 시 UUID v7 자동 생성.
 */
class LettuceStrategicSuspendLeaderElection(
    connection: StatefulRedisConnection<String, String>,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderElection {

    companion object : KLogging()

    private val registry = LettuceSuspendCandidateRegistry(connection)

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        registry.updateResult(lockName, nodeId, result)

    /**
     * 전략으로 리더를 선출하고 winner 인 경우에만 [action] 을 실행합니다.
     *
     * 분산 락 없이 결정론적 선출을 사용하므로 [options] 의 waitTime/leaseTime 은 적용되지 않습니다.
     * 후보 등록 시 TTL 을 직접 설정하세요.
     *
     * [CancellationException] 은 실패로 처리하지 않으며 failureCount 를 증가시키지 않습니다.
     *
     * @return [action] 실행 결과, 후보 없거나 다른 노드가 winner 이면 `null`
     */
    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
    ): T? {
        val candidates = try {
            listCandidates(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "[$lockName] 후보 목록 조회 실패 — 선출 skip" }
            return null
        }
        val result = strategy.elect(candidates)
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

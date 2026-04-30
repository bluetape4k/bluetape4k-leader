package io.bluetape4k.leader.lettuce

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import java.time.Duration

/**
 * Lettuce 백엔드 기반 [StrategicLeaderElection] 구현체입니다.
 *
 * ## 선출 방식
 * 분산 락 없이 결정론적 전략을 사용합니다.
 * 모든 노드가 동일한 후보 목록에 동일한 전략을 적용하면 동일한 winner 를 계산합니다.
 * winner 인 노드만 action 을 실행합니다.
 *
 * ## 주의
 * 후보 등록/만료 타이밍 차이로 노드마다 다른 후보 목록을 볼 수 있습니다.
 * 엄격한 상호 배제가 필요한 경우 [LettuceLeaderElection] (락 기반)을 사용하세요.
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 * @param nodeId 이 인스턴스의 노드 식별자. 미지정 시 UUID v7 자동 생성.
 */
class LettuceStrategicLeaderElection(
    connection: StatefulRedisConnection<String, String>,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicLeaderElection {

    companion object : KLogging()

    private val registry = LettuceCandidateRegistry(connection)

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        registry.registerCandidate(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) =
        registry.unregisterCandidate(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> =
        registry.listCandidates(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
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
    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        val result = strategy.elect(listCandidates(lockName))
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

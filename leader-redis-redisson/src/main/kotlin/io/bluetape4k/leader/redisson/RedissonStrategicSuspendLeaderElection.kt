package io.bluetape4k.leader.redisson

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElection
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import java.time.Duration

/**
 * Redisson 백엔드 기반 [StrategicSuspendLeaderElection] 구현체입니다.
 *
 * Redisson 블로킹 호출은 [Dispatchers.IO] 에서 실행합니다.
 * [CancellationException] 은 작업 실패가 아니므로 failureCount 를 증가시키지 않고 재전파합니다.
 *
 * @param redissonClient Redisson 클라이언트
 * @param nodeId 이 인스턴스의 노드 식별자. 미지정 시 UUID v7 자동 생성.
 */
class RedissonStrategicSuspendLeaderElection(
    redissonClient: RedissonClient,
    override val nodeId: String = Uuid.V7.nextBase62(),
) : StrategicSuspendLeaderElection {

    companion object : KLogging()

    private val registry = RedissonCandidateRegistry(redissonClient)

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        withContext(Dispatchers.IO) { registry.registerCandidate(lockName, info, ttl) }

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) =
        withContext(Dispatchers.IO) { registry.unregisterCandidate(lockName, nodeId) }

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> =
        withContext(Dispatchers.IO) { registry.listCandidates(lockName) }

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        withContext(Dispatchers.IO) { registry.updateResult(lockName, nodeId, result) }

    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
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
            updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

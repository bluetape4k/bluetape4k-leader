package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonStrategicLeaderElectionTest : AbstractRedissonLeaderTest() {

    private lateinit var node1: RedissonStrategicLeaderElection
    private lateinit var node2: RedissonStrategicLeaderElection
    private lateinit var node3: RedissonStrategicLeaderElection

    @BeforeEach
    fun setup() {
        node1 = RedissonStrategicLeaderElection(redissonClient, "node-1")
        node2 = RedissonStrategicLeaderElection(redissonClient, "node-2")
        node3 = RedissonStrategicLeaderElection(redissonClient, "node-3")
    }

    @Test
    fun `FIFO - 가장 먼저 등록된 노드가 action 실행`() {
        val lockName = randomName()
        val t0 = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-1", registeredAt = t0),
            CandidateInfo("node-2", registeredAt = t0.plusMillis(10)),
            CandidateInfo("node-3", registeredAt = t0.plusMillis(20)),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val counter = AtomicInteger(0)
        val r1 = node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }
        val r2 = node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }
        val r3 = node3.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }

        r1.shouldNotBeNull()
        r2.shouldBeNull()
        r3.shouldBeNull()
        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `후보 없으면 null 반환`() {
        val lockName = randomName()
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
    }

    @Test
    fun `TTL 만료 후 후보 자동 제거`() {
        val lockName = randomName()
        val ttl = Duration.ofMillis(300)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.listCandidates(lockName).size shouldBeEqualTo 1

        Thread.sleep(500)
        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }

    @Test
    fun `unregisterCandidate - 등록 해제 후 목록에서 제거`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.registerCandidate(lockName, CandidateInfo("node-2"))
        node1.unregisterCandidate(lockName, "node-1")

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().nodeId shouldBeEqualTo "node-2"
    }

    @Test
    fun `updateResult - SUCCESS 후 successCount 증가`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `runIfLeader SUCCESS 후 successCount 자동 증가`() {
        val lockName = randomName()
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `Scored IdleTime - 가장 오래 쉰 노드가 winner로 선출`() {
        val lockName = randomName()
        val now = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-1", lastCompletionTime = now.minusSeconds(10)),
            CandidateInfo("node-2", lastCompletionTime = now.minusSeconds(100)),
            CandidateInfo("node-3", lastCompletionTime = now.minusSeconds(50)),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(IdleTimeScorer)

        // 모든 노드가 동일한 후보 목록에서 동일한 winner 계산 (결정론적)
        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-2"

        // winner 노드만 action 실행
        val r2 = node2.runIfLeader(lockName, strategy) { "node-2 ran" }
        r2.shouldNotBeNull()

        // updateResult 후 node-2 lastCompletionTime = now → 다음 라운드에서 node-2는 idle=0
        // node-1(idle≈10s)은 여전히 winner가 아님 (node-3 idle≈50s가 다음 winner)
        val r1 = node1.runIfLeader(lockName, strategy) { "node-1 ran" }
        r1.shouldBeNull()
    }

    @Test
    fun `Scored SuccessRate - 성공률 높은 노드 선출`() {
        val lockName = randomName()
        val candidates = listOf(
            CandidateInfo("node-1", successCount = 1, failureCount = 9),   // 10%
            CandidateInfo("node-2", successCount = 9, failureCount = 1),   // 90%
            CandidateInfo("node-3", successCount = 5, failureCount = 5),   // 50%
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(SuccessRateScorer)

        // 결정론적 선출 검증
        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-2"

        val counter = AtomicInteger(0)
        node1.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node2.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node3.runIfLeader(lockName, strategy) { counter.incrementAndGet() }

        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `Weighted scorer - 성공률 우선 winner 선출`() {
        val lockName = randomName()
        val candidates = listOf(
            CandidateInfo("node-1", successCount = 10, failureCount = 0),  // 100%
            CandidateInfo("node-2", successCount = 0, failureCount = 10),  // 0%
            CandidateInfo("node-3", successCount = 5, failureCount = 5),   // 50%
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        // 성공률 weight 높게 → node-1 선출
        val scorer = WeightedScorer(IdleTimeScorer to 0.1, SuccessRateScorer to 0.9)
        val strategy = ScoredElectionStrategy(scorer)

        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-1"

        val counter = AtomicInteger(0)
        node1.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node2.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node3.runIfLeader(lockName, strategy) { counter.incrementAndGet() }

        counter.get() shouldBeEqualTo 1
    }
}

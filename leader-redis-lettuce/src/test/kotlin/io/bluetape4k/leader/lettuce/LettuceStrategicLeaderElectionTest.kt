package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.awaitility.kotlin.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceStrategicLeaderElectionTest: AbstractLettuceLeaderTest() {

    private lateinit var node1: LettuceStrategicLeaderElection
    private lateinit var node2: LettuceStrategicLeaderElection
    private lateinit var node3: LettuceStrategicLeaderElection

    @BeforeEach
    fun setup() {
        node1 = LettuceStrategicLeaderElection(connection, "node-1")
        node2 = LettuceStrategicLeaderElection(connection, "node-2")
        node3 = LettuceStrategicLeaderElection(connection, "node-3")
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

        await.atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50))
            .until { node1.listCandidates(lockName).isEmpty() }
    }

    @Test
    fun `updateResult 후 TTL 보존 - 항목이 여전히 만료됨`() {
        val lockName = randomName()
        val ttl = Duration.ofMillis(500)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        await.atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50))
            .until { node1.listCandidates(lockName).isEmpty() }
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
    fun `unregisterCandidate - 존재하지 않는 nodeId 는 무시됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.unregisterCandidate(lockName, "ghost-node")

        node1.listCandidates(lockName).size shouldBeEqualTo 1
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
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `단일 후보는 항상 자신이 선출됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "executed" }
        result shouldBeEqualTo "executed"
    }

    @Test
    fun `action 예외 시 failureCount 증가 및 예외 전파`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertThrows<LeaderGroupElectionException> {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw LeaderGroupElectionException("intentional")
            }
        }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `동일 nodeId 재등록 시 CandidateInfo 갱신됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 3))
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 10))

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().successCount shouldBeEqualTo 10L
    }

    @Test
    fun `서로 다른 lockName 은 독립적인 후보 풀`() {
        val lock1 = randomName()
        val lock2 = randomName()

        node1.registerCandidate(lock1, CandidateInfo("node-1"))
        node1.registerCandidate(lock1, CandidateInfo("node-2"))
        node1.registerCandidate(lock2, CandidateInfo("node-3"))

        node1.listCandidates(lock1).size shouldBeEqualTo 2
        node1.listCandidates(lock2).size shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - 존재하지 않는 nodeId 는 무시됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.updateResult(lockName, "ghost-node", CandidateResult.SUCCESS)

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        node1.listCandidates(lockName).first().successCount shouldBeEqualTo 0L
    }

    @Test
    fun `updateResult - TTL 만료 후 호출 시 좀비 항목 생성 없음`() {
        val lockName = randomName()
        val ttl = Duration.ofMillis(200)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        await.atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50))
            .until { node1.listCandidates(lockName).isEmpty() }

        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }

    @Test
    fun `registerCandidate - Duration ZERO 는 TTL 없는 영구 키`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"), Duration.ZERO)

        Thread.sleep(100)
        node1.listCandidates(lockName).size shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - failureCount 누적 증가`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 3L
    }

    @Test
    fun `FIFO - 동일 registeredAt 시 nodeId 사전순 선출`() {
        val lockName = randomName()
        val t0 = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-c", registeredAt = t0),
            CandidateInfo("node-a", registeredAt = t0),
            CandidateInfo("node-b", registeredAt = t0),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }

        val winner = FifoElectionStrategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `CandidateInfo - Instant 필드 직렬화 라운드트립`() {
        val lockName = randomName()
        val t0 = Instant.now()
        val original = CandidateInfo(
            nodeId = "node-1",
            registeredAt = t0,
            lastStartTime = t0.plusMillis(100),
            lastCompletionTime = t0.plusMillis(200),
            successCount = 7L,
            failureCount = 3L,
        )
        node1.registerCandidate(lockName, original)

        val retrieved = node1.listCandidates(lockName).first()
        retrieved.nodeId shouldBeEqualTo original.nodeId
        retrieved.successCount shouldBeEqualTo original.successCount
        retrieved.failureCount shouldBeEqualTo original.failureCount
        retrieved.registeredAt.toEpochMilli() shouldBeEqualTo original.registeredAt.toEpochMilli()
        retrieved.lastCompletionTime?.toEpochMilli() shouldBeEqualTo original.lastCompletionTime?.toEpochMilli()
    }

    @Test
    fun `CandidateInfo - metadata 특수문자 직렬화 라운드트립`() {
        val lockName = randomName()
        val original = CandidateInfo(
            nodeId = "node-1",
            metadata = mapOf(
                "k|ey" to "va,lue=1",
                "pct" to "100%",
                "plain" to "normal",
            ),
        )
        node1.registerCandidate(lockName, original)

        val retrieved = node1.listCandidates(lockName).first()
        retrieved.metadata shouldBeEqualTo original.metadata
    }

    @Test
    fun `Scored SuccessRate - 성공률 높은 노드 선출`() {
        val lockName = randomName()
        val candidates = listOf(
            CandidateInfo("node-1", successCount = 1, failureCount = 9),  // 10%
            CandidateInfo("node-2", successCount = 9, failureCount = 1),  // 90%
            CandidateInfo("node-3", successCount = 5, failureCount = 5),  // 50%
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(SuccessRateScorer)
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

package io.bluetape4k.leader.local

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
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalStrategicLeaderElectionTest {

    private val lockName = "test-lock"

    private lateinit var node1: LocalStrategicLeaderElection
    private lateinit var node2: LocalStrategicLeaderElection
    private lateinit var node3: LocalStrategicLeaderElection

    @BeforeEach
    fun setup() {
        node1 = LocalStrategicLeaderElection("node-1")
        node2 = LocalStrategicLeaderElection("node-2")
        node3 = LocalStrategicLeaderElection("node-3")
    }

    private fun registerAll(vararg nodes: LocalStrategicLeaderElection, registeredAt: Instant = Instant.now()) {
        nodes.forEachIndexed { i, node ->
            node.registerCandidate(lockName, CandidateInfo(
                nodeId = node.nodeId,
                registeredAt = registeredAt.plusMillis(i.toLong() * 10),
            ))
        }
    }

    @Test
    fun `FIFO - node1 이 가장 먼저 등록하면 node1 만 action 실행`() {
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node2.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))
        node3.registerCandidate(lockName, CandidateInfo("node-3", registeredAt = t0.plusMillis(20)))

        // 3개 노드 모두 같은 후보 목록 공유 (in-memory 싱글 registry 아님 → 각 노드의 registry 에 등록 필요)
        // node2, node3 에도 전체 후보 등록
        node2.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node2.registerCandidate(lockName, CandidateInfo("node-3", registeredAt = t0.plusMillis(20)))
        node3.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node3.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))

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
    fun `updateResult - SUCCESS 후 successCount 증가`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
        updated.failureCount shouldBeEqualTo 0L
    }

    @Test
    fun `updateResult - FAILURE 후 failureCount 증가`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 0L
        updated.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `runIfLeader - action 성공 시 successCount 자동 증가`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val info = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        info.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `runIfLeader - action 예외 시 failureCount 자동 증가 후 예외 재전파`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        runCatching {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw IllegalStateException("oops")
            }
        }.isFailure.shouldBeTrue()

        val info = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        info.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `ScoredIdleTime - 가장 오래 쉰 노드가 선출`() {
        val now = Instant.now()
        val longIdle = CandidateInfo("node-1", lastCompletionTime = now.minusSeconds(300))
        val shortIdle = CandidateInfo("node-2", lastCompletionTime = now.minusSeconds(10))

        // node1 이 오래 쉬었음
        node1.registerCandidate(lockName, longIdle)
        node1.registerCandidate(lockName, shortIdle)
        node2.registerCandidate(lockName, longIdle)
        node2.registerCandidate(lockName, shortIdle)

        val counter = AtomicInteger(0)
        val r1 = node1.runIfLeader(lockName, ScoredElectionStrategy(IdleTimeScorer)) { counter.incrementAndGet() }
        val r2 = node2.runIfLeader(lockName, ScoredElectionStrategy(IdleTimeScorer)) { counter.incrementAndGet() }

        r1.shouldNotBeNull()
        r2.shouldBeNull()
        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `후보 0명이면 runIfLeader null 반환`() {
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
    }

    @Test
    fun `unregisterCandidate - 등록 해제 후 후보 목록에서 제거`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.unregisterCandidate(lockName, "node-1")

        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
    }

    @Test
    fun `단일 후보는 항상 자신이 선출됨`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "executed" }
        result shouldBeEqualTo "executed"
    }

    @Test
    fun `동일 nodeId 재등록 시 CandidateInfo 갱신됨`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 3))
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 10))

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().successCount shouldBeEqualTo 10L
    }

    @Test
    fun `서로 다른 lockName 은 독립적인 후보 풀`() {
        val lock1 = "lock-alpha"
        val lock2 = "lock-beta"
        node1.registerCandidate(lock1, CandidateInfo("node-1"))
        node1.registerCandidate(lock1, CandidateInfo("node-2"))
        node1.registerCandidate(lock2, CandidateInfo("node-3"))

        node1.listCandidates(lock1).size shouldBeEqualTo 2
        node1.listCandidates(lock2).size shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - 존재하지 않는 nodeId 는 무시됨`() {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.updateResult(lockName, "ghost-node", CandidateResult.SUCCESS)

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        node1.listCandidates(lockName).first().successCount shouldBeEqualTo 0L
    }

    @Test
    fun `IdleTime - updateResult 후 winner 변경됨`() {
        val now = Instant.now()
        val c1 = CandidateInfo("node-1", lastCompletionTime = now.minusSeconds(100))
        val c2 = CandidateInfo("node-2", lastCompletionTime = now.minusSeconds(10))

        node1.registerCandidate(lockName, c1)
        node1.registerCandidate(lockName, c2)

        val strategy = ScoredElectionStrategy(IdleTimeScorer)

        // 초기: node-1 이 더 오래 쉬었음 → winner
        strategy.elect(node1.listCandidates(lockName)).winner?.nodeId shouldBeEqualTo "node-1"

        // node-1 작업 후 lastCompletionTime = now → idle≈0
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        // 다음 라운드: node-2 가 더 오래 쉬었음 → winner 변경
        strategy.elect(node1.listCandidates(lockName)).winner?.nodeId shouldBeEqualTo "node-2"
    }

    @Test
    fun `FIFO - 동일 registeredAt 시 nodeId 사전순 선출`() {
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-c", registeredAt = t0))
        node1.registerCandidate(lockName, CandidateInfo("node-a", registeredAt = t0))
        node1.registerCandidate(lockName, CandidateInfo("node-b", registeredAt = t0))

        val winner = FifoElectionStrategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `runIfLeader - winner 아닌 노드는 action 실행 안 함`() {
        val t0 = Instant.now()
        // node2 가 나중에 등록 → FIFO 에서 탈락
        node2.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node2.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))

        val result = node2.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
        node2.listCandidates(lockName).first { it.nodeId == "node-2" }.successCount shouldBeEqualTo 0L
    }

    @Test
    fun `SuccessRate - 성공률 높은 노드 선출`() {
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

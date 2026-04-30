package io.bluetape4k.leader.local

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
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
}

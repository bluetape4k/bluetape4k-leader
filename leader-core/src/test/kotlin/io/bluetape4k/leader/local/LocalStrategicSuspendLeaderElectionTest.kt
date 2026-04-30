package io.bluetape4k.leader.local

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import kotlinx.coroutines.test.runTest
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
class LocalStrategicSuspendLeaderElectionTest {

    private val lockName = "test-suspend-lock"

    private lateinit var node1: LocalStrategicSuspendLeaderElection
    private lateinit var node2: LocalStrategicSuspendLeaderElection

    @BeforeEach
    fun setup() {
        node1 = LocalStrategicSuspendLeaderElection("node-1")
        node2 = LocalStrategicSuspendLeaderElection("node-2")
    }

    @Test
    fun `FIFO - node1 먼저 등록하면 node1 만 action 실행`() = runTest {
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node1.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))
        node2.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node2.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))

        val counter = AtomicInteger(0)
        val r1 = node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }
        val r2 = node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }

        r1.shouldNotBeNull()
        r2.shouldBeNull()
        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - SUCCESS 후 successCount 증가`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `후보 0명이면 runIfLeader null 반환`() = runTest {
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
    }

    @Test
    fun `unregisterCandidate - 등록 해제 후 목록에서 제거`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.unregisterCandidate(lockName, "node-1")
        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
    }
}

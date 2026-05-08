package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class LocalStrategicSuspendLeaderElectorTest {

    private val lockName = "test-suspend-lock-" + Base58.randomString(8)

    private lateinit var node1: LocalStrategicSuspendLeaderElector
    private lateinit var node2: LocalStrategicSuspendLeaderElector
    private lateinit var node3: LocalStrategicSuspendLeaderElector

    @BeforeEach
    fun setup() {
        node1 = LocalStrategicSuspendLeaderElector("node-1")
        node2 = LocalStrategicSuspendLeaderElector("node-2")
        node3 = LocalStrategicSuspendLeaderElector("node-3")
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

    @Test
    fun `CancellationException - 코루틴 취소 시 failureCount 증가 없음`() = runSuspendIO {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        var caughtCancellation = false
        try {
            withTimeout(50L.milliseconds) {
                node1.runIfLeader(lockName, FifoElectionStrategy) {
                    delay(10_000L.milliseconds) // 타임아웃보다 훨씬 긴 지연
                }
            }
        } catch (e: TimeoutCancellationException) {
            caughtCancellation = true
        }
        caughtCancellation.shouldBeTrue()
        val candidate = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        candidate.failureCount shouldBeEqualTo 0L
    }

    @Test
    fun `action 예외 시 failureCount 증가 및 예외 재전파`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        runCatching {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw LeaderElectionException("intentional error")
            }
        }.isFailure.shouldBeTrue()

        val info = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        info.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `동일 nodeId 재등록 시 CandidateInfo 갱신됨`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 3))
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 10))

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().successCount shouldBeEqualTo 10L
    }

    @Test
    fun `서로 다른 lockName 은 독립적인 후보 풀`() = runTest {
        val lock1 = "lock-alpha"
        val lock2 = "lock-beta"
        node1.registerCandidate(lock1, CandidateInfo("node-1"))
        node1.registerCandidate(lock1, CandidateInfo("node-2"))
        node1.registerCandidate(lock2, CandidateInfo("node-3"))

        node1.listCandidates(lock1).size shouldBeEqualTo 2
        node1.listCandidates(lock2).size shouldBeEqualTo 1
    }

    @Test
    fun `단일 후보는 항상 자신이 선출됨`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "executed" }
        result shouldBeEqualTo "executed"
    }

    @Test
    fun `FIFO - 동일 registeredAt 시 nodeId 사전순 선출`() = runTest {
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-c", registeredAt = t0))
        node1.registerCandidate(lockName, CandidateInfo("node-a", registeredAt = t0))
        node1.registerCandidate(lockName, CandidateInfo("node-b", registeredAt = t0))

        val winner = FifoElectionStrategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `SuccessRate - 성공률 높은 노드 선출`() = runTest {
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
    fun `동시 coroutine 실행 - 정확히 1개 노드만 실행`() = runTest {
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
        coroutineScope {
            val jobs = listOf(
                async { node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node3.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
            )
            jobs.awaitAll()
        }
        counter.get() shouldBeEqualTo 1
    }
}

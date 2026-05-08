package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.awaitility.untilSuspending
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
import kotlinx.coroutines.withTimeout
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.awaitility.kotlin.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceStrategicSuspendLeaderElectorTest: AbstractLettuceLeaderTest() {

    private lateinit var node1: LettuceStrategicSuspendLeaderElector
    private lateinit var node2: LettuceStrategicSuspendLeaderElector
    private lateinit var node3: LettuceStrategicSuspendLeaderElector

    @BeforeEach
    fun setup() {
        node1 = LettuceStrategicSuspendLeaderElector(connection, "node-1")
        node2 = LettuceStrategicSuspendLeaderElector(connection, "node-2")
        node3 = LettuceStrategicSuspendLeaderElector(connection, "node-3")
    }

    @Test
    fun `FIFO - 가장 먼저 등록된 노드가 action 실행`() = runSuspendIO {
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
    fun `후보 없으면 null 반환`() = runSuspendIO {
        val lockName = randomName()
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
    }

    @Test
    fun `TTL 만료 후 후보 자동 제거`() = runSuspendIO {
        val lockName = randomName()
        val ttl = 300.milliseconds

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.listCandidates(lockName).size shouldBeEqualTo 1

        await.atMost(2.seconds).withPollInterval(50.milliseconds) untilSuspending {
            node1.listCandidates(lockName).isEmpty()
        }
    }

    @Test
    fun `updateResult 후 TTL 보존 - 항목이 여전히 만료됨`() = runSuspendIO {
        val lockName = randomName()
        val ttl = 500.milliseconds

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        await.atMost(2.seconds).withPollInterval(50.milliseconds) untilSuspending {
            node1.listCandidates(lockName).isEmpty()
        }
    }

    @Test
    fun `unregisterCandidate - 등록 해제 후 목록에서 제거`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.registerCandidate(lockName, CandidateInfo("node-2"))
        node1.unregisterCandidate(lockName, "node-1")

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().nodeId shouldBeEqualTo "node-2"
    }

    @Test
    fun `unregisterCandidate - 존재하지 않는 nodeId 는 무시됨`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.unregisterCandidate(lockName, "ghost-node")

        node1.listCandidates(lockName).size shouldBeEqualTo 1
    }

    @Test
    fun `runIfLeader SUCCESS 후 successCount 자동 증가`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `updateResult - TTL 만료 후 호출 시 좀비 항목 생성 없음`() = runSuspendIO {
        val lockName = randomName()
        val ttl = 200.milliseconds

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        await.atMost(2.seconds).withPollInterval(50.milliseconds) untilSuspending {
            node1.listCandidates(lockName).isEmpty()
        }

        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }

    @Test
    fun `registerCandidate - Duration ZERO 는 TTL 없는 영구 키`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"), Duration.ZERO)

        delay(100L.milliseconds)
        node1.listCandidates(lockName).size shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - failureCount 누적 증가`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 3L
    }

    @Test
    fun `CancellationException - failureCount 증가 없음`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50L.milliseconds) {
                node1.runIfLeader(lockName, FifoElectionStrategy) {
                    delay(10_000L.milliseconds)
                }
            }
        }

        val candidate = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        candidate.failureCount shouldBeEqualTo 0L
    }

    @Test
    fun `action 예외 시 failureCount 증가`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertFailsWith<LeaderElectionException> {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw LeaderElectionException("intentional")
            }
        }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `metadata 특수문자 직렬화 라운드트립`() = runSuspendIO {
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
    fun `Scored SuccessRate - 성공률 높은 노드 선출`() = runSuspendIO {
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
    fun `동시 coroutine 실행 - 정확히 1개 노드만 실행`() = runSuspendIO {
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
        coroutineScope {
            listOf(
                async { node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node3.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
            ).awaitAll()
        }
        counter.get() shouldBeEqualTo 1
    }
}

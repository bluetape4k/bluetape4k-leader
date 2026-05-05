package io.bluetape4k.leader.redisson

import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.awaitility.kotlin.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class RedissonStrategicSuspendLeaderElectorTest: AbstractRedissonLeaderTest() {

    private lateinit var node1: RedissonStrategicSuspendLeaderElector
    private lateinit var node2: RedissonStrategicSuspendLeaderElector
    private lateinit var node3: RedissonStrategicSuspendLeaderElector

    @BeforeEach
    fun setup() {
        node1 = RedissonStrategicSuspendLeaderElector(redissonClient, "node-1")
        node2 = RedissonStrategicSuspendLeaderElector(redissonClient, "node-2")
        node3 = RedissonStrategicSuspendLeaderElector(redissonClient, "node-3")
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
        val ttl = Duration.ofMillis(300)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.listCandidates(lockName).size shouldBeEqualTo 1

        await.atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)) untilSuspending {
            node1.listCandidates(lockName).isEmpty()
        }
    }

    @Test
    fun `CancellationException - failureCount 증가 없음`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertThrows<TimeoutCancellationException> {
            withTimeout(50L.milliseconds) {
                node1.runIfLeader(lockName, FifoElectionStrategy) {
                    delay(10_000L.milliseconds)
                }
            }
        }

        val candidate = node1.listCandidates(lockName).firstOrNull { it.nodeId == "node-1" }
        candidate.shouldNotBeNull()
        candidate.failureCount shouldBeEqualTo 0L
    }

    @Test
    fun `action 예외 시 failureCount 증가 및 예외 전파`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertThrows<LeaderElectionException> {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw LeaderElectionException("intentional error")
            }
        }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 1L
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
            val jobs = listOf(
                async { node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
                async { node3.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() } },
            )
            jobs.awaitAll()
        }
        counter.get() shouldBeEqualTo 1
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
    fun `runIfLeader SUCCESS 후 successCount 자동 증가`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }
}

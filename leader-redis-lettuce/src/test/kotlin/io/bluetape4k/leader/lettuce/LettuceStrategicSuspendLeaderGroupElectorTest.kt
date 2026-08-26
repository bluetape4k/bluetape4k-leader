package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.StrategicGroupElectionResult
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredGroupElectionStrategy
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.support.closeSafe
import io.lettuce.core.codec.StringCodec
import org.awaitility.kotlin.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceStrategicSuspendLeaderGroupElectorTest : AbstractLettuceLeaderTest() {

    private lateinit var node1: LettuceStrategicSuspendLeaderGroupElector
    private lateinit var node2: LettuceStrategicSuspendLeaderGroupElector
    private lateinit var node3: LettuceStrategicSuspendLeaderGroupElector

    @BeforeEach
    fun setup() {
        node1 = LettuceStrategicSuspendLeaderGroupElector(connection, "node-1")
        node2 = LettuceStrategicSuspendLeaderGroupElector(connection, "node-2")
        node3 = LettuceStrategicSuspendLeaderGroupElector(connection, "node-3")
    }

    @Test
    fun `top two Redis coroutine winner만 action을 실행한다`() = runSuspendIO {
        val lockName = randomName()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val candidates = listOf(
            CandidateInfo("node-1", registeredAt = t0),
            CandidateInfo("node-2", registeredAt = t0.plusSeconds(1)),
            CandidateInfo("node-3", registeredAt = t0.plusSeconds(2)),
        )
        listOf(node1, node2, node3).forEach { elector ->
            candidates.forEach { elector.registerCandidate(lockName, it) }
        }

        val counter = AtomicInteger(0)
        node1.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }
            .shouldNotBeNull()
        node2.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }
            .shouldNotBeNull()
        node3.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }
            .shouldBeNull()

        counter.get() shouldBeEqualTo 2
    }

    @Test
    fun `Redis coroutine candidate TTL은 실행 후에도 유지된다`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId), 500.milliseconds)
        node1.runIfLeader(lockName, FifoGroupElectionStrategy) { "ok" }

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        await.atMost(2.seconds).withPollInterval(50.milliseconds) untilSuspending {
            node1.listCandidates(lockName).isEmpty()
        }
    }

    @Test
    fun `영구 후보는 유한 TTL 후보 만료 후에도 조회된다`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("persistent-node"))
        node1.registerCandidate(lockName, CandidateInfo("finite-node"), 300.milliseconds)

        await.atMost(2.seconds).withPollInterval(50.milliseconds) untilSuspending {
            node1.listCandidates(lockName).map { it.nodeId } == listOf("persistent-node")
        }
    }

    @Test
    fun `동시 suspend group 결과 갱신은 성공과 실패 카운터와 winner를 보존한다`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.registerCandidate(lockName, CandidateInfo("node-2", successCount = 1, failureCount = 9))

        val connections = (1..8).map { client.connect(StringCodec.UTF8) }
        try {
            val electors = connections.map { LettuceStrategicSuspendLeaderGroupElector(it, "node-1") }
            val actions = electors.flatMap { elector ->
                listOf<suspend () -> Unit>(
                    { elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS) },
                    { elector.updateResult(lockName, "node-1", CandidateResult.FAILURE) },
                )
            }
            val workers = actions.size
            val rounds = 20
            SuspendedJobTester()
                .workers(workers)
                .rounds(rounds)
                .addAll(actions)
                .run()

            val candidates = node1.listCandidates(lockName)
            val updated = candidates.first { it.nodeId == "node-1" }
            val expectedEach = (workers * rounds / 2).toLong()
            updated.successCount shouldBeEqualTo expectedEach
            updated.failureCount shouldBeEqualTo expectedEach
            updated.successRate shouldBeEqualTo 0.5
            ScoredGroupElectionStrategy(SuccessRateScorer)
                .elect(candidates, maxLeaders = 1)
                .winners
                .first()
                .nodeId shouldBeEqualTo "node-1"
        } finally {
            connections.forEach { it.closeSafe() }
        }
    }

    @Test
    fun `custom strategy가 후보 기준 목록 밖 winner를 반환하면 action 전에 거부한다`() = runSuspendIO {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))
        val invalidStrategy = GroupElectionStrategy { _, _ ->
            StrategicGroupElectionResult(
                winners = listOf(CandidateInfo("ghost")),
                eliminations = emptyList(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            node1.runIfLeader(lockName, invalidStrategy) { error("실행되면 안 됨") }
        }
    }
}

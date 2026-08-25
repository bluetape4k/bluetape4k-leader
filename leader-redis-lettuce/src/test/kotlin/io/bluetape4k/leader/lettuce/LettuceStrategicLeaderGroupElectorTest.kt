package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.StrategicGroupElectionResult
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import org.awaitility.kotlin.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceStrategicLeaderGroupElectorTest : AbstractLettuceLeaderTest() {

    private lateinit var node1: LettuceStrategicLeaderGroupElector
    private lateinit var node2: LettuceStrategicLeaderGroupElector
    private lateinit var node3: LettuceStrategicLeaderGroupElector

    @BeforeEach
    fun setup() {
        node1 = LettuceStrategicLeaderGroupElector(connection, "node-1")
        node2 = LettuceStrategicLeaderGroupElector(connection, "node-2")
        node3 = LettuceStrategicLeaderGroupElector(connection, "node-3")
    }

    @Test
    fun `top two Redis winner만 action을 실행한다`() {
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
    fun `Redis candidate TTL은 strategic group 실행 후에도 유지된다`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId), 500.milliseconds)
        node1.runIfLeader(lockName, FifoGroupElectionStrategy) { "ok" }

        node1.listCandidates(lockName).size shouldBeEqualTo 1
        await.atMost(2.seconds).withPollInterval(50.milliseconds)
            .until { node1.listCandidates(lockName).isEmpty() }
    }

    @Test
    fun `strategic single과 group 후보 레지스트리는 key namespace를 공유하지 않는다`() {
        val lockName = randomName()
        val single = LettuceStrategicLeaderElector(connection, "single-node")
        single.registerCandidate(lockName, CandidateInfo("single-node"))
        node1.registerCandidate(lockName, CandidateInfo("group-node"))

        single.listCandidates(lockName).map { it.nodeId } shouldBeEqualTo listOf("single-node")
        node1.listCandidates(lockName).map { it.nodeId } shouldBeEqualTo listOf("group-node")
    }

    @Test
    fun `영구 후보는 유한 TTL 후보 만료 후에도 조회된다`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("persistent-node"))
        node1.registerCandidate(lockName, CandidateInfo("finite-node"), 300.milliseconds)

        await.atMost(2.seconds).withPollInterval(50.milliseconds)
            .until {
                node1.listCandidates(lockName).map { it.nodeId } == listOf("persistent-node")
            }
    }

    @Test
    fun `custom strategy가 후보 기준 목록 밖 winner를 반환하면 action 전에 거부한다`() {
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

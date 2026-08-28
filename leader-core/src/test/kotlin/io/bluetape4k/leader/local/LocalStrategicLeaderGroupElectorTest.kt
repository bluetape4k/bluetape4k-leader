package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.StrategicGroupElectionResult
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class LocalStrategicLeaderGroupElectorTest {

    private val lockName = "strategic-group-" + Base58.randomString(8)
    private lateinit var node1: LocalStrategicLeaderGroupElector
    private lateinit var node2: LocalStrategicLeaderGroupElector
    private lateinit var node3: LocalStrategicLeaderGroupElector

    @BeforeEach
    fun setup() {
        node1 = LocalStrategicLeaderGroupElector("node-1")
        node2 = LocalStrategicLeaderGroupElector("node-2")
        node3 = LocalStrategicLeaderGroupElector("node-3")
    }

    private fun registerAll() {
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val candidates = listOf(
            CandidateInfo("node-1", registeredAt = t0),
            CandidateInfo("node-2", registeredAt = t0.plusSeconds(1)),
            CandidateInfo("node-3", registeredAt = t0.plusSeconds(2)),
        )
        listOf(node1, node2, node3).forEach { elector ->
            candidates.forEach { elector.registerCandidate(lockName, it) }
        }
    }

    @Test
    fun `top two winner만 action을 실행하고 세 번째 node는 null을 반환한다`() {
        registerAll()
        val counter = AtomicInteger(0)
        val r1 = node1.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }
        val r2 = node2.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }
        val r3 = node3.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { counter.incrementAndGet() }

        r1 shouldBeEqualTo 1
        r2 shouldBeEqualTo 2
        r3.shouldBeNull()
        counter.get() shouldBeEqualTo 2
    }

    @Test
    fun `선택되지 않은 node는 successCount를 갱신하지 않는다`() {
        registerAll()
        node3.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) {
            error("실행되면 안 됨")
        }.shouldBeNull()

        node3.listCandidates(lockName)
            .first { it.nodeId == node3.nodeId }
            .successCount shouldBeEqualTo 0L
    }

    @Test
    fun `선택된 node의 action 예외는 failureCount를 갱신하고 재전파한다`() {
        registerAll()

        assertFailsWith<IllegalStateException> {
            node1.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) {
                error("boom")
            }
        }

        node1.listCandidates(lockName)
            .first { it.nodeId == node1.nodeId }
            .failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `refreshCandidate - 없는 후보는 새로 등록하지 않는다`() {
        node1.refreshCandidate(
            lockName,
            CandidateInfo("ghost", metadata = mapOf("heartbeat" to "missing")),
        )

        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
    }

    @Test
    fun `refreshCandidate - 결과 통계와 등록 시각을 보존하고 metadata만 갱신한다`() {
        node1.registerCandidate(
            lockName,
            CandidateInfo(
                nodeId = node1.nodeId,
                registeredAt = Instant.parse("2026-01-01T00:00:00Z"),
                successCount = 3,
                failureCount = 2,
                metadata = mapOf("version" to "old"),
            ),
        )
        node1.updateResult(lockName, node1.nodeId, io.bluetape4k.leader.strategy.CandidateResult.SUCCESS)
        val beforeRefresh = node1.listCandidates(lockName).single()

        node1.refreshCandidate(
            lockName,
            CandidateInfo(node1.nodeId, metadata = mapOf("version" to "new")),
        )

        val refreshed = node1.listCandidates(lockName).single()
        refreshed.registeredAt shouldBeEqualTo beforeRefresh.registeredAt
        refreshed.lastCompletionTime shouldBeEqualTo beforeRefresh.lastCompletionTime
        refreshed.successCount shouldBeEqualTo beforeRefresh.successCount
        refreshed.failureCount shouldBeEqualTo beforeRefresh.failureCount
        refreshed.metadata shouldBeEqualTo mapOf("version" to "new")
    }

    @Test
    fun `refreshCandidate와 updateResult 동시 호출에서도 결과 카운터를 잃지 않는다`() {
        val workers = 8
        val rounds = 100
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        MultithreadingTester()
            .workers(workers)
            .rounds(rounds)
            .add {
                node1.updateResult(lockName, node1.nodeId, CandidateResult.SUCCESS)
                node1.refreshCandidate(lockName, CandidateInfo(node1.nodeId, metadata = mapOf("heartbeat" to "ok")))
            }
            .run()

        node1.listCandidates(lockName).single().successCount shouldBeEqualTo (workers * rounds).toLong()
    }

    @Test
    fun `refreshCandidate와 unregisterCandidate 동시 호출에서도 후보를 되살리지 않는다`() {
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        MultithreadingTester()
            .workers(2)
            .rounds(100)
            .addAll(
                { node1.refreshCandidate(lockName, CandidateInfo(node1.nodeId, metadata = mapOf("heartbeat" to "ok"))) },
                { node1.unregisterCandidate(lockName, node1.nodeId) },
            )
            .run()

        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
    }

    @Test
    fun `CancellationException은 결과를 갱신하지 않고 재전파한다`() {
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        assertFailsWith<CancellationException> {
            node1.runIfLeader(lockName, FifoGroupElectionStrategy) {
                throw CancellationException("cancel")
            }
        }

        val candidate = node1.listCandidates(lockName).single()
        candidate.successCount shouldBeEqualTo 0L
        candidate.failureCount shouldBeEqualTo 0L
        (candidate.lastCompletionTime == null).shouldBeTrue()
    }

    @Test
    fun `custom strategy가 후보 기준 목록 밖의 winner를 반환하면 action 전에 거부한다`() {
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

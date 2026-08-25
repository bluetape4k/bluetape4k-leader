package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class LocalStrategicSuspendLeaderGroupElectorTest {

    private val lockName = "strategic-suspend-group-" + Base58.randomString(8)
    private lateinit var node1: LocalStrategicSuspendLeaderGroupElector
    private lateinit var node2: LocalStrategicSuspendLeaderGroupElector
    private lateinit var node3: LocalStrategicSuspendLeaderGroupElector

    @BeforeEach
    fun setup() {
        node1 = LocalStrategicSuspendLeaderGroupElector("node-1")
        node2 = LocalStrategicSuspendLeaderGroupElector("node-2")
        node3 = LocalStrategicSuspendLeaderGroupElector("node-3")
    }

    private suspend fun registerAll() {
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
    fun `top two coroutine winner만 action을 실행한다`() = runTest {
        registerAll()
        val counter = AtomicInteger(0)
        val results = coroutineScope {
            listOf(node1, node2, node3).map { elector ->
                async {
                    elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) {
                        counter.incrementAndGet()
                    }
                }
            }.awaitAll()
        }

        results.filterNotNull().size shouldBeEqualTo 2
        results[2].shouldBeNull()
        counter.get() shouldBeEqualTo 2
    }

    @Test
    fun `선택된 coroutine action 예외는 failureCount를 갱신한다`() = runTest {
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        assertFailsWith<IllegalStateException> {
            node1.runIfLeader(lockName, FifoGroupElectionStrategy) { error("boom") }
        }

        node1.listCandidates(lockName).single().failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `coroutine CancellationException은 failureCount 없이 재전파한다`() = runSuspendIO {
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        assertFailsWith<CancellationException> {
            node1.runIfLeader(lockName, FifoGroupElectionStrategy) {
                throw CancellationException("cancel")
            }
        }

        val candidate = node1.listCandidates(lockName).single()
        candidate.successCount shouldBeEqualTo 0L
        candidate.failureCount shouldBeEqualTo 0L
    }
}

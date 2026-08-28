package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
    fun `refreshCandidate - 없는 후보는 새로 등록하지 않는다`() = runTest {
        node1.refreshCandidate(
            lockName,
            CandidateInfo("ghost", metadata = mapOf("heartbeat" to "missing")),
        )

        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
    }

    @Test
    fun `refreshCandidate - 결과 통계와 등록 시각을 보존하고 metadata만 갱신한다`() = runTest {
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
    fun `refreshCandidate와 updateResult 동시 호출에서도 결과 카운터를 잃지 않는다`() = runSuspendIO {
        val workers = 8
        val rounds = 100
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        coroutineScope {
            List(workers) {
                launch(Dispatchers.Default) {
                    repeat(rounds) {
                        node1.updateResult(
                            lockName,
                            node1.nodeId,
                            io.bluetape4k.leader.strategy.CandidateResult.SUCCESS,
                        )
                        node1.refreshCandidate(
                            lockName,
                            CandidateInfo(node1.nodeId, metadata = mapOf("heartbeat" to "ok")),
                        )
                    }
                }
            }.joinAll()
        }

        node1.listCandidates(lockName).single().successCount shouldBeEqualTo (workers * rounds).toLong()
    }

    @Test
    fun `refreshCandidate와 unregisterCandidate 동시 호출에서도 후보를 되살리지 않는다`() = runSuspendIO {
        node1.registerCandidate(lockName, CandidateInfo(node1.nodeId))

        coroutineScope {
            listOf(
                launch(Dispatchers.Default) {
                    repeat(100) {
                        node1.refreshCandidate(
                            lockName,
                            CandidateInfo(node1.nodeId, metadata = mapOf("heartbeat" to "ok")),
                        )
                    }
                },
                launch(Dispatchers.Default) {
                    repeat(100) {
                        node1.unregisterCandidate(lockName, node1.nodeId)
                    }
                },
            ).joinAll()
        }

        node1.listCandidates(lockName).isEmpty().shouldBeTrue()
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

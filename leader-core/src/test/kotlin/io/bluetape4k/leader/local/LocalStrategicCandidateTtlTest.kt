package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class LocalStrategicCandidateTtlTest {

    private val lockName = "local-strategic-ttl-${Base58.randomString(8)}"
    private val registeredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun expiredCandidate() = CandidateInfo("node-1", registeredAt = registeredAt)

    private fun backupCandidate() = CandidateInfo("node-2", registeredAt = registeredAt.plusSeconds(1))

    private fun refreshedCandidate() = CandidateInfo("node-1", metadata = mapOf("heartbeat" to "fresh"))

    @Test
    fun `blocking single TTL 만료는 refresh로 되살아나지 않고 winner를 바꾼다`() {
        val elector = LocalStrategicLeaderElector("node-1")
        elector.registerCandidate(lockName, expiredCandidate(), 1.milliseconds)
        elector.registerCandidate(lockName, backupCandidate())
        Thread.sleep(30)

        elector.refreshCandidate(lockName, refreshedCandidate())

        elector.listCandidates(lockName).map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-2")
        var actionRan = false
        elector.runIfLeader(lockName, FifoElectionStrategy) { actionRan = true }.shouldBeNull()
        actionRan.shouldBeFalse()
    }

    @Test
    fun `blocking group TTL 만료는 refresh로 되살아나지 않고 winner를 바꾼다`() {
        val elector = LocalStrategicLeaderGroupElector("node-1")
        elector.registerCandidate(lockName, expiredCandidate(), 1.milliseconds)
        elector.registerCandidate(lockName, backupCandidate())
        Thread.sleep(30)

        elector.refreshCandidate(lockName, refreshedCandidate())

        elector.listCandidates(lockName).map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-2")
        var actionRan = false
        elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) {
            actionRan = true
        }.shouldBeNull()
        actionRan.shouldBeFalse()
    }

    @Test
    fun `suspend single TTL 만료는 refresh로 되살아나지 않고 winner를 바꾼다`() = runSuspendIO {
        val elector = LocalStrategicSuspendLeaderElector("node-1")
        elector.registerCandidate(lockName, expiredCandidate(), 1.milliseconds)
        elector.registerCandidate(lockName, backupCandidate())
        delay(30.milliseconds)

        elector.refreshCandidate(lockName, refreshedCandidate())

        elector.listCandidates(lockName).map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-2")
        var actionRan = false
        elector.runIfLeader(lockName, FifoElectionStrategy) { actionRan = true }.shouldBeNull()
        actionRan.shouldBeFalse()
    }

    @Test
    fun `suspend group TTL 만료는 refresh로 되살아나지 않고 winner를 바꾼다`() = runSuspendIO {
        val elector = LocalStrategicSuspendLeaderGroupElector("node-1")
        elector.registerCandidate(lockName, expiredCandidate(), 1.milliseconds)
        elector.registerCandidate(lockName, backupCandidate())
        delay(30.milliseconds)

        elector.refreshCandidate(lockName, refreshedCandidate())

        elector.listCandidates(lockName).map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-2")
        var actionRan = false
        elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) {
            actionRan = true
        }.shouldBeNull()
        actionRan.shouldBeFalse()
    }

    @Test
    fun `positive TTL은 정확한 단조 시각 경계에서 만료된다`() {
        val nowNanos = AtomicLong(100L)
        val registry = LocalStrategicCandidateRegistry(nowNanos::get)
        registry.register(CandidateInfo("node-1"), 5.nanoseconds)

        registry.list().map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-1")
        nowNanos.set(104L)
        registry.list().map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-1")
        nowNanos.set(105L)
        registry.list().shouldBeEmpty()
    }

    @Test
    fun `refresh는 live 후보의 통계와 실행 시각을 보존하고 metadata와 TTL을 갱신한다`() {
        val nowNanos = AtomicLong(100L)
        val registry = LocalStrategicCandidateRegistry(nowNanos::get)
        val registeredAt = Instant.parse("2026-01-01T00:00:00Z")
        val initial = CandidateInfo(
            nodeId = "node-1",
            registeredAt = registeredAt,
            lastStartTime = registeredAt.plusSeconds(1),
            lastCompletionTime = registeredAt.plusSeconds(2),
            successCount = 3,
            failureCount = 1,
            metadata = mapOf("heartbeat" to "old"),
        )
        registry.register(initial, 5.nanoseconds)
        nowNanos.set(102L)
        val beforeRefresh = registry.list().single()

        nowNanos.set(104L)
        registry.refresh(CandidateInfo("node-1", metadata = mapOf("heartbeat" to "new")), 10.nanoseconds)

        val refreshed = registry.list().single()
        refreshed.registeredAt shouldBeEqualTo beforeRefresh.registeredAt
        refreshed.lastStartTime shouldBeEqualTo beforeRefresh.lastStartTime
        refreshed.lastCompletionTime shouldBeEqualTo beforeRefresh.lastCompletionTime
        refreshed.successCount shouldBeEqualTo beforeRefresh.successCount
        refreshed.failureCount shouldBeEqualTo beforeRefresh.failureCount
        refreshed.metadata shouldBeEqualTo mapOf("heartbeat" to "new")

        nowNanos.set(113L)
        registry.list().map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-1")
        nowNanos.set(114L)
        registry.list().shouldBeEmpty()
    }

    @Test
    fun `만료된 후보 refresh는 재등록하지 않고 zero TTL은 만료를 끈다`() {
        val nowNanos = AtomicLong(100L)
        val registry = LocalStrategicCandidateRegistry(nowNanos::get)
        registry.register(CandidateInfo("expired"), 5.nanoseconds)
        nowNanos.set(105L)

        registry.refresh(CandidateInfo("expired", metadata = mapOf("heartbeat" to "late")), Duration.ZERO)
        registry.list().shouldBeEmpty()

        registry.register(CandidateInfo("unbounded"), Duration.ZERO)
        nowNanos.set(1_000_000L)
        registry.list().map(CandidateInfo::nodeId) shouldBeEqualTo listOf("unbounded")
    }

    @Test
    fun `live 후보의 zero TTL refresh는 기존 만료 기한을 제거한다`() {
        val nowNanos = AtomicLong(100L)
        val registry = LocalStrategicCandidateRegistry(nowNanos::get)
        registry.register(CandidateInfo("node-1"), 5.nanoseconds)
        nowNanos.set(102L)

        registry.refresh(CandidateInfo("node-1", metadata = mapOf("heartbeat" to "new")), Duration.ZERO)
        nowNanos.set(1_000_000L)

        registry.list().map(CandidateInfo::nodeId) shouldBeEqualTo listOf("node-1")
    }

    @Test
    fun `result update는 후보 TTL을 연장하지 않는다`() {
        val nowNanos = AtomicLong(100L)
        val registry = LocalStrategicCandidateRegistry(nowNanos::get)
        registry.register(CandidateInfo("node-1"), 5.nanoseconds)
        nowNanos.set(104L)

        registry.updateResult("node-1", CandidateResult.SUCCESS)
        nowNanos.set(105L)

        registry.list().shouldBeEmpty()
    }
}

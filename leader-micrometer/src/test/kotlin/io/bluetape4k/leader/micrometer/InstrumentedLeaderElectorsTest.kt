package io.bluetape4k.leader.micrometer

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstrumentedLeaderElectorsTest {

    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setup() {
        registry = SimpleMeterRegistry()
    }

    @Test
    fun `LeaderElector - action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)

        val result = election.runIfLeader("job-lock") { "done" }

        result shouldBeEqualTo "done"
        acquiredCount("job-lock") shouldBeEqualTo 1.0
        durationCount("job-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("job-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - action 이 null 을 반환해도 acquired 로 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)

        val result = election.runIfLeader<String?>("nullable-job") { null }

        result.shouldBeNull()
        acquiredCount("nullable-job") shouldBeEqualTo 1.0
        notAcquiredCount("nullable-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - 리더 미획득 시 not_acquired 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = false), registry)

        val result = election.runIfLeader("skip-job") { "not-called" }

        result.shouldBeNull()
        notAcquiredCount("skip-job") shouldBeEqualTo 1.0
        acquiredCount("skip-job") shouldBeEqualTo 0.0
        durationCount("skip-job") shouldBeEqualTo 0L
    }

    @Test
    fun `LeaderElector - async action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)

        val result = election.runAsyncIfLeader("async-job", sameThreadExecutor) {
            CompletableFuture.completedFuture("done")
        }.join()

        result shouldBeEqualTo "done"
        acquiredCount("async-job") shouldBeEqualTo 1.0
        durationCount("async-job") shouldBeGreaterOrEqualTo 1L
        activeValue("async-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - async 리더 미획득 시 not_acquired 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = false), registry)

        val result = election.runAsyncIfLeader("async-skip-job", sameThreadExecutor) {
            CompletableFuture.completedFuture("not-called")
        }.join()

        result.shouldBeNull()
        notAcquiredCount("async-skip-job") shouldBeEqualTo 1.0
        acquiredCount("async-skip-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - 고정 lockName 태그를 사용`() {
        val election = InstrumentedLeaderElector(
            delegate = StubLeaderElector(elected = true),
            registry = registry,
            lockName = "configured-lock",
        )

        election.runIfLeader("runtime-lock") { "done" }

        acquiredCount("configured-lock") shouldBeEqualTo 1.0
        acquiredCount("runtime-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - action 예외 전파 후 active 가 0 으로 복구`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)

        val result = runCatching {
            election.runIfLeader("failed-job") {
                throw IllegalStateException("boom")
            }
        }

        result.isFailure.shouldBeTrue()
        acquiredCount("failed-job") shouldBeEqualTo 1.0
        durationCount("failed-job") shouldBeGreaterOrEqualTo 1L
        activeValue("failed-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderGroupElector - action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = true), registry)

        val result = election.runIfLeader("group-lock") { 42 }

        result shouldBeEqualTo 42
        acquiredCount("group-lock") shouldBeEqualTo 1.0
        durationCount("group-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("group-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderGroupElector - async action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = true), registry)

        val result = election.runAsyncIfLeader("group-async-lock", sameThreadExecutor) {
            CompletableFuture.completedFuture(42)
        }.join()

        result shouldBeEqualTo 42
        acquiredCount("group-async-lock") shouldBeEqualTo 1.0
        durationCount("group-async-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("group-async-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderGroupElector - 슬롯 미획득 시 not_acquired 기록`() {
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = false), registry)

        val result = election.runIfLeader("group-skip-lock") { 42 }

        result.shouldBeNull()
        notAcquiredCount("group-skip-lock") shouldBeEqualTo 1.0
        acquiredCount("group-skip-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `SuspendLeaderElector - action 실행 시 acquired duration active 기록`() = runSuspendIO {
        val election = InstrumentedSuspendLeaderElector(StubSuspendLeaderElector(elected = true), registry)

        val result = election.runIfLeader("suspend-lock") { "done" }

        result shouldBeEqualTo "done"
        acquiredCount("suspend-lock") shouldBeEqualTo 1.0
        durationCount("suspend-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("suspend-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `SuspendLeaderElector - 리더 미획득 시 not_acquired 기록`() = runSuspendIO {
        val election = InstrumentedSuspendLeaderElector(StubSuspendLeaderElector(elected = false), registry)

        val result = election.runIfLeader("suspend-skip-lock") { "not-called" }

        result.shouldBeNull()
        notAcquiredCount("suspend-skip-lock") shouldBeEqualTo 1.0
        acquiredCount("suspend-skip-lock") shouldBeEqualTo 0.0
    }

    private fun acquiredCount(lockName: String): Double =
        registry.find(MicrometerNames.METER_LEADER_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter()
            ?.count() ?: 0.0

    private fun notAcquiredCount(lockName: String): Double =
        registry.find(MicrometerNames.METER_LEADER_NOT_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter()
            ?.count() ?: 0.0

    private fun durationCount(lockName: String): Long =
        registry.find(MicrometerNames.METER_LEADER_DURATION)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .timer()
            ?.count() ?: 0L

    private fun activeValue(lockName: String): Double =
        registry.find(MicrometerNames.METER_LEADER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()
            ?.value() ?: 0.0

    private val sameThreadExecutor = Executor { command -> command.run() }

    private class StubLeaderElector(
        private val elected: Boolean,
    ): LeaderElector {

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (elected) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            CompletableFuture.completedFuture(if (elected) action().join() else null)
    }

    private class StubLeaderGroupElector(
        private val elected: Boolean,
    ): LeaderGroupElector {

        override val maxLeaders: Int = 2

        override fun activeCount(lockName: String): Int = 0

        override fun availableSlots(lockName: String): Int = maxLeaders

        override fun state(lockName: String): LeaderGroupState =
            LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (elected) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            CompletableFuture.completedFuture(if (elected) action().join() else null)
    }

    private class StubSuspendLeaderElector(
        private val elected: Boolean,
    ): SuspendLeaderElector {

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
            if (elected) action() else null
    }
}

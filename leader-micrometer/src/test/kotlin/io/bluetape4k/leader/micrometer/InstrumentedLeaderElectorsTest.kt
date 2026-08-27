package io.bluetape4k.leader.micrometer

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstrumentedLeaderElectorsTest {

    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setup() {
        registry = SimpleMeterRegistry()
    }

    @Test
    fun `LeaderElector - default constructor redacts runtime lock names`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)

        election.runIfLeader("tenant-a") { "done" }
        election.runIfLeader("tenant-b") { "done" }

        acquiredCount("redacted-lock") shouldBeEqualTo 2.0
        acquiredCount("tenant-a") shouldBeEqualTo 0.0
    }

    @Test
    fun `single leader decorators preserve audit state capability`() {
        InstrumentedLeaderElector(
            StubLeaderElector(elected = true, supportsAuditLeaderState = true),
            registry,
        ).supportsAuditLeaderState.shouldBeTrue()

        InstrumentedSuspendLeaderElector(
            StubSuspendLeaderElector(elected = true, supportsAuditLeaderState = true),
            registry,
        ).supportsAuditLeaderState.shouldBeTrue()
    }

    @Test
    fun `instrumented decorators preserve nullable backend diagnostics provider`() {
        val leaderDelegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {}
        val groupDelegate = object :
            LeaderGroupElector by StubLeaderGroupElector(elected = true),
            LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {}
        val suspendDelegate = object :
            SuspendLeaderElector by StubSuspendLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {}

        val wrappers: List<Any> = listOf(
            InstrumentedLeaderElector(leaderDelegate, registry),
            InstrumentedLeaderGroupElector(groupDelegate, registry),
            InstrumentedSuspendLeaderElector(suspendDelegate, registry),
        )

        wrappers.forEach { wrapper ->
            val provider = (wrapper as? LeaderBackendDiagnosticsAware)?.backendDiagnosticsProvider

            provider.shouldNotBeNull().backendDescriptor shouldBe
                    LocalLeaderBackendDiagnostics.backendDescriptor
        }

        val wrapperWithoutProvider: Any =
            InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)
        (wrapperWithoutProvider as? LeaderBackendDiagnosticsAware)
            ?.backendDiagnosticsProvider
            .shouldBeNull()
    }

    @Test
    fun `active diagnostics emits one bounded connectivity counter with sanitized backend tags`() {
        val provider = RecordingDiagnosticsProvider(
            connectivity = LeaderBackendConnectivity.up(Instant.EPOCH),
            backendName = "redis-prod.example:6380/token",
        )
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val election = InstrumentedLeaderElector(
            delegate = delegate,
            registry = registry,
            tagOptions = LeaderMetricTagOptions(
                backendName = LeaderMetricTagRule(redactedValue = "redacted-backend"),
            ),
        )

        val decorated = (election as LeaderBackendDiagnosticsAware).backendDiagnosticsProvider
            .shouldNotBeNull()
        decorated.checkConnectivity(100.milliseconds)

        val meter = registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY).counter()
            .shouldNotBeNull()
        meter.count() shouldBeEqualTo 1.0
        meter.id.tags.map { it.key }.toSet() shouldBeEqualTo setOf(
            LeaderMetricTagOptions.TAG_BACKEND_NAME,
            MicrometerNames.TAG_BACKEND_STATUS,
            MicrometerNames.TAG_BACKEND_REASON,
        )
        meter.id.getTag(LeaderMetricTagOptions.TAG_BACKEND_NAME) shouldBeEqualTo "redacted-backend"
        meter.id.getTag(MicrometerNames.TAG_BACKEND_STATUS) shouldBeEqualTo LeaderBackendConnectivityStatus.UP.name
        meter.id.getTag(MicrometerNames.TAG_BACKEND_REASON) shouldBeEqualTo LeaderBackendConnectivityReason.CONNECTED.name
    }

    @Test
    fun `passive diagnostics does not create connectivity counter`() {
        val provider = RecordingDiagnosticsProvider()
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val election = InstrumentedLeaderElector(delegate, registry)

        (election as LeaderBackendDiagnosticsAware).backendDiagnosticsProvider
            .shouldNotBeNull()
            .diagnostics()

        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY).meters().isEmpty().shouldBeTrue()
    }

    @Test
    fun `active diagnostics records one counter for each execution model`() {
        val blockingDelegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by RecordingDiagnosticsProvider() {}
        val groupDelegate = object :
            LeaderGroupElector by StubLeaderGroupElector(elected = true),
            LeaderBackendDiagnosticsProvider by RecordingDiagnosticsProvider() {}
        val suspendDelegate = object :
            SuspendLeaderElector by StubSuspendLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by RecordingDiagnosticsProvider() {}
        val wrappers = listOf<LeaderBackendDiagnosticsAware>(
            InstrumentedLeaderElector(blockingDelegate, registry),
            InstrumentedLeaderGroupElector(groupDelegate, registry),
            InstrumentedSuspendLeaderElector(suspendDelegate, registry),
        )

        wrappers.forEach { wrapper ->
            wrapper.backendDiagnosticsProvider
                .shouldNotBeNull()
                .diagnostics(probe = true, timeout = 100.milliseconds)
        }

        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(LeaderMetricTagOptions.TAG_BACKEND_NAME, "test-backend")
            .tag(MicrometerNames.TAG_BACKEND_STATUS, LeaderBackendConnectivityStatus.UP.name)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.CONNECTED.name)
            .counter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 3.0
    }

    @Test
    fun `concurrent active diagnostics creates one meter and counts every probe`() {
        val provider = RecordingDiagnosticsProvider()
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val election = InstrumentedLeaderElector(delegate, registry)
        val decorated = (election as LeaderBackendDiagnosticsAware).backendDiagnosticsProvider
            .shouldNotBeNull()

        MultithreadingTester()
            .workers(8)
            .rounds(25)
            .add { decorated.checkConnectivity(100.milliseconds) }
            .run()

        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.CONNECTED.name)
            .counter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 200.0
        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.CONNECTED.name)
            .meters()
            .size shouldBeEqualTo 1
    }

    @Test
    fun `decorated provider preserves exception identity and records bounded fallback reason`() {
        val failure = IllegalStateException("endpoint=https://redis-prod.example token=secret")
        val provider = RecordingDiagnosticsProvider(failure = failure)
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val decorated = (InstrumentedLeaderElector(delegate, registry) as LeaderBackendDiagnosticsAware)
            .backendDiagnosticsProvider
            .shouldNotBeNull()

        val thrown = assertFailsWith<IllegalStateException> {
            decorated.checkConnectivity(100.milliseconds)
        }

        thrown shouldBeSameInstanceAs failure
        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.PROVIDER_EXCEPTION.name)
            .counter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `active diagnostics preserves ordinary exception identity and records one fallback`() {
        val failure = IllegalArgumentException("endpoint=https://redis-prod.example token=secret")
        val provider = RecordingDiagnosticsProvider(failure = failure)
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val decorated = (InstrumentedLeaderElector(delegate, registry) as LeaderBackendDiagnosticsAware)
            .backendDiagnosticsProvider
            .shouldNotBeNull()

        val thrown = assertFailsWith<IllegalArgumentException> {
            decorated.diagnostics(probe = true, timeout = 100.milliseconds)
        }

        thrown shouldBeSameInstanceAs failure
        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.PROVIDER_EXCEPTION.name)
            .counter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `interruption and Error are rethrown without fallback metric`() {
        val failures = listOf<Throwable>(
            InterruptedException("interrupted"),
            AssertionError("fatal"),
        )

        failures.forEach { failure ->
            val provider = RecordingDiagnosticsProvider(failure = failure)
            val delegate = object :
                LeaderElector by StubLeaderElector(elected = true),
                LeaderBackendDiagnosticsProvider by provider {}
            val decorated = (InstrumentedLeaderElector(delegate, registry) as LeaderBackendDiagnosticsAware)
                .backendDiagnosticsProvider
                .shouldNotBeNull()

            val thrown = assertFailsWith<Throwable> {
                decorated.checkConnectivity(100.milliseconds)
            }

            thrown shouldBeSameInstanceAs failure
        }

        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.PROVIDER_EXCEPTION.name)
            .counter()
            .shouldBeNull()
    }

    @Test
    fun `decorating an instrumented provider does not double count`() {
        val provider = RecordingDiagnosticsProvider()
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val inner = InstrumentedLeaderElector(delegate, registry)
        val outer = InstrumentedLeaderElector(inner, registry)

        (outer as LeaderBackendDiagnosticsAware).backendDiagnosticsProvider
            .shouldNotBeNull()
            .checkConnectivity(100.milliseconds)

        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.CONNECTED.name)
            .counter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `cancellation is rethrown without synthetic provider exception metric`() {
        val cancellation = CancellationException("cancelled")
        val provider = RecordingDiagnosticsProvider(failure = cancellation)
        val delegate = object :
            LeaderElector by StubLeaderElector(elected = true),
            LeaderBackendDiagnosticsProvider by provider {}
        val decorated = (InstrumentedLeaderElector(delegate, registry) as LeaderBackendDiagnosticsAware)
            .backendDiagnosticsProvider
            .shouldNotBeNull()

        val thrown = assertFailsWith<CancellationException> {
            decorated.checkConnectivity(100.milliseconds)
        }

        thrown shouldBeSameInstanceAs cancellation
        registry.find(MicrometerNames.METER_BACKEND_CONNECTIVITY)
            .tag(MicrometerNames.TAG_BACKEND_REASON, LeaderBackendConnectivityReason.PROVIDER_EXCEPTION.name)
            .counter()
            .shouldBeNull()
    }

    @Test
    fun `LeaderElector - fixed lockName is sanitized after selection`() {
        val election = InstrumentedLeaderElector(
            delegate = StubLeaderElector(elected = true),
            registry = registry,
            lockName = "configured-lock",
        )

        election.runIfLeader("runtime-lock") { "done" }

        acquiredCount("redacted-lock") shouldBeEqualTo 1.0
        acquiredCount("configured-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - concurrent redacted first use creates one acquired meter`(): Unit {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)
        val sequence = AtomicInteger()

        MultithreadingTester()
            .workers(8)
            .rounds(50)
            .add {
                election.runIfLeader("tenant-${sequence.incrementAndGet()}") { "done" }
            }
            .run()

        acquiredCount("redacted-lock") shouldBeEqualTo 400.0
        acquiredCounters("redacted-lock") shouldBeEqualTo 1
    }

    @Test
    fun `LeaderElector - action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader("job-lock") { "done" }

        result shouldBeEqualTo "done"
        acquiredCount("job-lock") shouldBeEqualTo 1.0
        durationCount("job-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("job-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - action 이 null 을 반환해도 acquired 로 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader<String?>("nullable-job") { null }

        result.shouldBeNull()
        acquiredCount("nullable-job") shouldBeEqualTo 1.0
        notAcquiredCount("nullable-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - 리더 미획득 시 not_acquired 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = false), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader("skip-job") { "not-called" }

        result.shouldBeNull()
        notAcquiredCount("skip-job") shouldBeEqualTo 1.0
        acquiredCount("skip-job") shouldBeEqualTo 0.0
        durationCount("skip-job") shouldBeEqualTo 0L
    }

    @Test
    fun `LeaderElector - async action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry, LeaderMetricTagOptions.Raw)

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
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = false), registry, LeaderMetricTagOptions.Raw)

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
            tagOptions = LeaderMetricTagOptions.Raw,
        )

        election.runIfLeader("runtime-lock") { "done" }

        acquiredCount("configured-lock") shouldBeEqualTo 1.0
        acquiredCount("runtime-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderElector - action 예외 전파 후 active 가 0 으로 복구`() {
        val election = InstrumentedLeaderElector(StubLeaderElector(elected = true), registry, LeaderMetricTagOptions.Raw)

        assertFailsWith<IllegalStateException> {
            election.runIfLeader("failed-job") {
                throw IllegalStateException("boom")
            }
        }

        acquiredCount("failed-job") shouldBeEqualTo 1.0
        durationCount("failed-job") shouldBeGreaterOrEqualTo 1L
        activeValue("failed-job") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderGroupElector - action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = true), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader("group-lock") { 42 }

        result shouldBeEqualTo 42
        acquiredCount("group-lock") shouldBeEqualTo 1.0
        durationCount("group-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("group-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `LeaderGroupElector - async action 실행 시 acquired duration active 기록`() {
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = true), registry, LeaderMetricTagOptions.Raw)

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
        val election = InstrumentedLeaderGroupElector(StubLeaderGroupElector(elected = false), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader("group-skip-lock") { 42 }

        result.shouldBeNull()
        notAcquiredCount("group-skip-lock") shouldBeEqualTo 1.0
        acquiredCount("group-skip-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `SuspendLeaderElector - action 실행 시 acquired duration active 기록`() = runSuspendIO {
        val election = InstrumentedSuspendLeaderElector(StubSuspendLeaderElector(elected = true), registry, LeaderMetricTagOptions.Raw)

        val result = election.runIfLeader("suspend-lock") { "done" }

        result shouldBeEqualTo "done"
        acquiredCount("suspend-lock") shouldBeEqualTo 1.0
        durationCount("suspend-lock") shouldBeGreaterOrEqualTo 1L
        activeValue("suspend-lock") shouldBeEqualTo 0.0
    }

    @Test
    fun `SuspendLeaderElector - 리더 미획득 시 not_acquired 기록`() = runSuspendIO {
        val election = InstrumentedSuspendLeaderElector(StubSuspendLeaderElector(elected = false), registry, LeaderMetricTagOptions.Raw)

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

    private fun acquiredCounters(lockName: String): Int =
        registry.find(MicrometerNames.METER_LEADER_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counters()
            .size

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

    private class RecordingDiagnosticsProvider(
        private val connectivity: LeaderBackendConnectivity = LeaderBackendConnectivity.up(Instant.EPOCH),
        backendName: String = "test-backend",
        private val failure: Throwable? = null,
    ) : LeaderBackendDiagnosticsProvider {

        override val backendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor.copy(backendId = backendName)

        override fun checkConnectivity(timeout: kotlin.time.Duration): LeaderBackendConnectivity {
            failure?.let { throw it }
            return connectivity
        }
    }

    private class StubLeaderElector(
        private val elected: Boolean,
        override val supportsAuditLeaderState: Boolean = false,
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
        override val supportsAuditLeaderState: Boolean = false,
    ): SuspendLeaderElector {

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
            if (elected) action() else null
    }
}

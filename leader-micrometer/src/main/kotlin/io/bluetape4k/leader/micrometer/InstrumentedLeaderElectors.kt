package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * `InstrumentedLeaderElector`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property delegate Micrometer observability 계약에서 사용하는 속성입니다.
 * @property lockName Micrometer observability 계약에서 사용하는 속성입니다.
 * @property tagSanitizer Micrometer observability 계약에서 사용하는 속성입니다.
 */
class InstrumentedLeaderElector private constructor(
    private val delegate: LeaderElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
    private val tagSanitizer: LeaderMetricTagSanitizer,
): LeaderElector by delegate, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveBackendDiagnosticsProvider()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    private val metrics = InstrumentedLeaderMetrics(registry)

    constructor(
        delegate: LeaderElector,
        registry: MeterRegistry,
        lockName: String? = null,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.Default)

    constructor(
        delegate: LeaderElector,
        registry: MeterRegistry,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, null, LeaderMetricTagSanitizer.from(tagOptions))

    constructor(
        delegate: LeaderElector,
        registry: MeterRegistry,
        lockName: String?,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.from(tagOptions))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val metricLockName = metricLockName(lockName)
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            metrics.recordAcquired(metricLockName) {
                action()
            }
        }
        if (!elected) {
            metrics.recordNotAcquired(metricLockName)
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val metricLockName = metricLockName(lockName)
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            metrics.recordAsyncAcquired(metricLockName) {
                action()
            }
        }.whenComplete { _, _ ->
            if (!elected.get()) {
                metrics.recordNotAcquired(metricLockName)
            }
        }
    }

    private fun metricLockName(requestedLockName: String): String =
        tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName ?: requestedLockName)
}

/**
 * `InstrumentedLeaderGroupElector`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property delegate Micrometer observability 계약에서 사용하는 속성입니다.
 * @property lockName Micrometer observability 계약에서 사용하는 속성입니다.
 * @property tagSanitizer Micrometer observability 계약에서 사용하는 속성입니다.
 */
class InstrumentedLeaderGroupElector private constructor(
    private val delegate: LeaderGroupElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
    private val tagSanitizer: LeaderMetricTagSanitizer,
): LeaderGroupElector by delegate, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveBackendDiagnosticsProvider()

    private val metrics = InstrumentedLeaderMetrics(registry)

    constructor(
        delegate: LeaderGroupElector,
        registry: MeterRegistry,
        lockName: String? = null,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.Default)

    constructor(
        delegate: LeaderGroupElector,
        registry: MeterRegistry,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, null, LeaderMetricTagSanitizer.from(tagOptions))

    constructor(
        delegate: LeaderGroupElector,
        registry: MeterRegistry,
        lockName: String?,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.from(tagOptions))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val metricLockName = metricLockName(lockName)
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            metrics.recordAcquired(metricLockName) {
                action()
            }
        }
        if (!elected) {
            metrics.recordNotAcquired(metricLockName)
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val metricLockName = metricLockName(lockName)
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            metrics.recordAsyncAcquired(metricLockName) {
                action()
            }
        }.whenComplete { _, _ ->
            if (!elected.get()) {
                metrics.recordNotAcquired(metricLockName)
            }
        }
    }

    private fun metricLockName(requestedLockName: String): String =
        tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName ?: requestedLockName)
}

/**
 * `InstrumentedSuspendLeaderElector`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property delegate Micrometer observability 계약에서 사용하는 속성입니다.
 * @property lockName Micrometer observability 계약에서 사용하는 속성입니다.
 * @property tagSanitizer Micrometer observability 계약에서 사용하는 속성입니다.
 */
class InstrumentedSuspendLeaderElector private constructor(
    private val delegate: SuspendLeaderElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
    private val tagSanitizer: LeaderMetricTagSanitizer,
): SuspendLeaderElector by delegate, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveBackendDiagnosticsProvider()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    private val metrics = InstrumentedLeaderMetrics(registry)

    constructor(
        delegate: SuspendLeaderElector,
        registry: MeterRegistry,
        lockName: String? = null,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.Default)

    constructor(
        delegate: SuspendLeaderElector,
        registry: MeterRegistry,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, null, LeaderMetricTagSanitizer.from(tagOptions))

    constructor(
        delegate: SuspendLeaderElector,
        registry: MeterRegistry,
        lockName: String?,
        tagOptions: LeaderMetricTagOptions,
    ): this(delegate, registry, lockName, LeaderMetricTagSanitizer.from(tagOptions))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val metricLockName = metricLockName(lockName)
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            metrics.recordSuspendAcquired(metricLockName) {
                action()
            }
        }
        if (!elected) {
            metrics.recordNotAcquired(metricLockName)
        }
        return result
    }

    private fun metricLockName(requestedLockName: String): String =
        tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName ?: requestedLockName)
}

private fun Any.resolveBackendDiagnosticsProvider(): LeaderBackendDiagnosticsProvider? =
    when (this) {
        is LeaderBackendDiagnosticsProvider -> this
        is LeaderBackendDiagnosticsAware -> backendDiagnosticsProvider
        else -> null
    }

private class InstrumentedLeaderMetrics(
    private val registry: MeterRegistry,
) {

    private val acquiredCounters = ConcurrentHashMap<String, Counter>()
    private val notAcquiredCounters = ConcurrentHashMap<String, Counter>()
    private val durationTimers = ConcurrentHashMap<String, Timer>()
    private val activeGauges = ConcurrentHashMap<String, AtomicInteger>()

    fun <T> recordAcquired(lockName: String, action: () -> T): T {
        acquiredCounter(lockName).increment()
        val active = activeGauge(lockName)
        active.incrementAndGet()
        val mark = TimeSource.Monotonic.markNow()
        try {
            return action()
        } finally {
            durationTimer(lockName).record(mark.elapsedNow().toJavaDuration())
            active.decrementAndGet()
        }
    }

    fun <T> recordAsyncAcquired(lockName: String, action: () -> CompletableFuture<T>): CompletableFuture<T> {
        acquiredCounter(lockName).increment()
        val active = activeGauge(lockName)
        active.incrementAndGet()
        val mark = TimeSource.Monotonic.markNow()
        return try {
            action().whenComplete { _, _ ->
                durationTimer(lockName).record(mark.elapsedNow().toJavaDuration())
                active.decrementAndGet()
            }
        } catch (e: Throwable) {
            durationTimer(lockName).record(mark.elapsedNow().toJavaDuration())
            active.decrementAndGet()
            CompletableFuture.failedFuture(e)
        }
    }

    suspend fun <T> recordSuspendAcquired(lockName: String, action: suspend () -> T): T {
        acquiredCounter(lockName).increment()
        val active = activeGauge(lockName)
        active.incrementAndGet()
        val mark = TimeSource.Monotonic.markNow()
        try {
            return action()
        } finally {
            durationTimer(lockName).record(mark.elapsedNow().toJavaDuration())
            active.decrementAndGet()
        }
    }

    fun recordNotAcquired(lockName: String) {
        notAcquiredCounter(lockName).increment()
    }

    private fun acquiredCounter(lockName: String): Counter =
        acquiredCounters.computeIfAbsent(lockName) {
            Counter.builder(MicrometerNames.METER_LEADER_ACQUIRED)
                .tag(MicrometerNames.TAG_LOCK_NAME, it)
                .register(registry)
        }

    private fun notAcquiredCounter(lockName: String): Counter =
        notAcquiredCounters.computeIfAbsent(lockName) {
            Counter.builder(MicrometerNames.METER_LEADER_NOT_ACQUIRED)
                .tag(MicrometerNames.TAG_LOCK_NAME, it)
                .register(registry)
        }

    private fun durationTimer(lockName: String): Timer =
        durationTimers.computeIfAbsent(lockName) {
            Timer.builder(MicrometerNames.METER_LEADER_DURATION)
                .tag(MicrometerNames.TAG_LOCK_NAME, it)
                .register(registry)
        }

    private fun activeGauge(lockName: String): AtomicInteger =
        activeGauges.computeIfAbsent(lockName) {
            val counter = AtomicInteger(0)
            Gauge.builder(MicrometerNames.METER_LEADER_ACTIVE, counter) { value -> value.get().toDouble() }
                .tag(MicrometerNames.TAG_LOCK_NAME, it)
                .register(registry)
            counter
        }
}

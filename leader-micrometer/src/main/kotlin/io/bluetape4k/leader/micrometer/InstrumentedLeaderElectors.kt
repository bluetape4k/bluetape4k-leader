package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
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
 * Decorator that instruments [LeaderElector] calls with Micrometer metrics.
 *
 * ## Behavior / Contract
 * - Does not alter [delegate]'s behavior or exception propagation.
 * - Records `shedlock.leader.acquired`, `shedlock.leader.duration`, and `shedlock.leader.active`
 *   when the actual [action] is executed in synchronous or asynchronous calls.
 * - Records `shedlock.leader.not_acquired` when [action] is not executed because the leader was not acquired.
 * - If [lockName] is specified, uses the fixed value for the `lock.name` tag on all metrics;
 *   if `null`, uses the lock name passed at call time.
 *
 * ```kotlin
 * val election = InstrumentedLeaderElector(delegate, registry)
 * val result = election.runIfLeader("nightly-job") { runJob() }
 * ```
 *
 * @param delegate The [LeaderElector] that performs the actual leader election
 * @param registry Micrometer [MeterRegistry] to register metrics against
 * @param lockName Fixed lock name for the metric tag. If `null`, uses the per-call lock name
 */
class InstrumentedLeaderElector(
    private val delegate: LeaderElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
): LeaderElector by delegate {

    private val metrics = InstrumentedLeaderMetrics(registry)

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
        lockName ?: requestedLockName
}

/**
 * Decorator that instruments [LeaderGroupElector] calls with Micrometer metrics.
 *
 * ## Behavior / Contract
 * - Delegates slot acquisition and state query behavior from [delegate] unchanged.
 * - Records `shedlock.leader.acquired`, `shedlock.leader.duration`, and `shedlock.leader.active`
 *   when a slot is acquired and [action] is executed in synchronous or asynchronous calls.
 * - Records `shedlock.leader.not_acquired` when a slot is not acquired.
 *
 * ```kotlin
 * val election = InstrumentedLeaderGroupElector(delegate, registry)
 * election.runIfLeader("batch-shard") { processShard() }
 * ```
 *
 * @param delegate The [LeaderGroupElector] that performs the actual multi-leader election
 * @param registry Micrometer [MeterRegistry] to register metrics against
 * @param lockName Fixed lock name for the metric tag. If `null`, uses the per-call lock name
 */
class InstrumentedLeaderGroupElector(
    private val delegate: LeaderGroupElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
): LeaderGroupElector by delegate {

    private val metrics = InstrumentedLeaderMetrics(registry)

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
        lockName ?: requestedLockName
}

/**
 * Decorator that instruments [SuspendLeaderElector] calls with Micrometer metrics.
 *
 * ## Behavior / Contract
 * - Does not alter [delegate]'s suspend execution, exception, or cancellation propagation.
 * - Records `shedlock.leader.acquired`, `shedlock.leader.duration`, and `shedlock.leader.active`
 *   when the actual suspend [action] is executed.
 * - Records `shedlock.leader.not_acquired` when [action] is not executed because the leader was not acquired.
 *
 * ```kotlin
 * val election = InstrumentedSuspendLeaderElector(delegate, registry)
 * val result = election.runIfLeader("sync-job") { syncData() }
 * ```
 *
 * @param delegate The [SuspendLeaderElector] that performs the actual leader election
 * @param registry Micrometer [MeterRegistry] to register metrics against
 * @param lockName Fixed lock name for the metric tag. If `null`, uses the per-call lock name
 */
class InstrumentedSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
    registry: MeterRegistry,
    private val lockName: String? = null,
): SuspendLeaderElector by delegate {

    private val metrics = InstrumentedLeaderMetrics(registry)

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
        lockName ?: requestedLockName
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

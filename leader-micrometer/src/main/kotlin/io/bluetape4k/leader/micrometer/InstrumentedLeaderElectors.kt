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
 * [LeaderElector] 호출을 Micrometer 메트릭으로 계측하는 데코레이터입니다.
 *
 * ## 동작/계약
 * - [delegate]의 동작과 예외 전파를 변경하지 않습니다.
 * - 동기/비동기 호출에서 실제 [action]이 실행된 경우 `shedlock.leader.acquired`, `shedlock.leader.duration`,
 *   `shedlock.leader.active`를 기록합니다.
 * - 리더를 획득하지 못해 [action]이 실행되지 않은 경우 `shedlock.leader.not_acquired`를 기록합니다.
 * - [lockName]이 지정되면 모든 메트릭의 `lock.name` 태그에 고정값을 사용하고,
 *   `null`이면 호출 시 전달된 lock 이름을 사용합니다.
 *
 * ```kotlin
 * val election = InstrumentedLeaderElector(delegate, registry)
 * val result = election.runIfLeader("nightly-job") { runJob() }
 * ```
 *
 * @param delegate 실제 리더 선출을 수행하는 [LeaderElector]
 * @param registry 메트릭을 등록할 Micrometer [MeterRegistry]
 * @param lockName 메트릭 태그에 사용할 고정 lock 이름. `null`이면 호출별 lock 이름 사용
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
 * [LeaderGroupElector] 호출을 Micrometer 메트릭으로 계측하는 데코레이터입니다.
 *
 * ## 동작/계약
 * - [delegate]의 슬롯 획득/상태 조회 동작을 그대로 위임합니다.
 * - 동기/비동기 호출에서 슬롯을 획득해 [action]이 실행된 경우 `shedlock.leader.acquired`, `shedlock.leader.duration`,
 *   `shedlock.leader.active`를 기록합니다.
 * - 슬롯을 획득하지 못한 경우 `shedlock.leader.not_acquired`를 기록합니다.
 *
 * ```kotlin
 * val election = InstrumentedLeaderGroupElector(delegate, registry)
 * election.runIfLeader("batch-shard") { processShard() }
 * ```
 *
 * @param delegate 실제 복수 리더 선출을 수행하는 [LeaderGroupElector]
 * @param registry 메트릭을 등록할 Micrometer [MeterRegistry]
 * @param lockName 메트릭 태그에 사용할 고정 lock 이름. `null`이면 호출별 lock 이름 사용
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
 * [SuspendLeaderElector] 호출을 Micrometer 메트릭으로 계측하는 데코레이터입니다.
 *
 * ## 동작/계약
 * - [delegate]의 suspend 실행, 예외, 취소 전파를 변경하지 않습니다.
 * - 실제 suspend [action]이 실행된 경우 `shedlock.leader.acquired`,
 *   `shedlock.leader.duration`, `shedlock.leader.active`를 기록합니다.
 * - 리더를 획득하지 못해 [action]이 실행되지 않은 경우 `shedlock.leader.not_acquired`를 기록합니다.
 *
 * ```kotlin
 * val election = InstrumentedSuspendLeaderElector(delegate, registry)
 * val result = election.runIfLeader("sync-job") { syncData() }
 * ```
 *
 * @param delegate 실제 리더 선출을 수행하는 [SuspendLeaderElector]
 * @param registry 메트릭을 등록할 Micrometer [MeterRegistry]
 * @param lockName 메트릭 태그에 사용할 고정 lock 이름. `null`이면 호출별 lock 이름 사용
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

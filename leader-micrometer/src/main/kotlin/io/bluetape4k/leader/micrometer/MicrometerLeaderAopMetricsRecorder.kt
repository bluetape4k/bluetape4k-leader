package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private typealias NotAcquiredKey = Pair<String, SkipReason>
private typealias FailedKey = Pair<String, String>

/**
 * `LeaderAopMetricsRecorder` 이벤트를 counter, timer, gauge로 기록하는 Micrometer 구현입니다.
 *
 * @property registry recorder가 생성한 meter를 등록하고 제거하는 `MeterRegistry`입니다.
 * @property tagSanitizer `lock.name` tag에 적용할 sanitization 정책입니다. 동적 lock 이름의 민감정보와 cardinality를 줄입니다.
 * @property removeUnregisteredMeters 명시적으로 등록하지 않은 lock 이름의 meter도 deregister 시 제거할지 나타냅니다. `Raw` lock-name 정책에서만 `true`가 됩니다.
 */
class MicrometerLeaderAopMetricsRecorder private constructor(
    private val registry: MeterRegistry,
    private val tagSanitizer: LeaderMetricTagSanitizer,
    private val removeUnregisteredMeters: Boolean,
): LeaderAopMetricsRecorder {

    companion object: KLogging()

    constructor(registry: MeterRegistry): this(
        registry = registry,
        tagSanitizer = LeaderMetricTagSanitizer.Default,
        removeUnregisteredMeters = false,
    )

    constructor(registry: MeterRegistry, tagOptions: LeaderMetricTagOptions): this(
        registry = registry,
        tagSanitizer = LeaderMetricTagSanitizer.from(tagOptions),
        removeUnregisteredMeters = tagOptions.lockName == LeaderMetricTagRule.Raw,
    )

    constructor(registry: MeterRegistry, tagSanitizer: LeaderMetricTagSanitizer): this(
        registry = registry,
        tagSanitizer = tagSanitizer,
        removeUnregisteredMeters = false,
    )

    private val attemptCounters = ConcurrentHashMap<String, Counter>()
    private val acquiredCounters = ConcurrentHashMap<String, Counter>()
    private val acquireTimers = ConcurrentHashMap<String, Timer>()
    private val notAcquiredCounters = ConcurrentHashMap<NotAcquiredKey, Counter>()
    private val executionTimers = ConcurrentHashMap<String, Timer>()
    private val failedCounters = ConcurrentHashMap<FailedKey, Counter>()
    private val activeGauges = ConcurrentHashMap<String, AtomicInteger>()
    private val explicitRegistrations = ConcurrentHashMap<String, MutableSet<String>>()

    override fun onLockAttempt(name: String, options: LeaderElectionOptions) {
        val tag = sanitizeLockName(name)
        attemptCounter(tag).increment()
    }

    override fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {
        val tag = sanitizeLockName(name)
        acquiredCounter(tag).increment()
        acquireTimer(tag).record(acquireElapsed.toJavaDuration())
    }

    override fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {
        val tag = sanitizeLockName(name)
        notAcquiredCounter(tag, reason).increment()
    }

    override fun onTaskStarted(name: String) {
        val tag = sanitizeLockName(name)
        activeGauge(tag).incrementAndGet()
    }

    override fun onTaskFinished(name: String, executionTime: Duration) {
        val tag = sanitizeLockName(name)
        try {
            executionTimer(tag).record(executionTime.toJavaDuration())
        } finally {
            activeGauge(tag).updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        val tag = sanitizeLockName(name)
        try {
            failedCounter(tag, throwable::class.simpleName ?: MicrometerNames.UNKNOWN_EXCEPTION).increment()
        } finally {
            // onTaskFailed 는 onTaskStarted 없이도 호출될 수 있다 (backend error path).
            // decrementAndGet 전에 값이 0 보다 큰지 확인해 음수 방지.
            activeGauge(tag).updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    /** 지정한 lock 이름의 기본 counter, timer, gauge를 미리 등록합니다. */
    fun registerMetricsFor(vararg lockNames: String) {
        lockNames.forEach { name ->
            val tag = sanitizeLockName(name)
            explicitRegistrations.compute(tag) { _, registeredNames ->
                (registeredNames ?: mutableSetOf()).also { it.add(name) }
            }
            // attemptCounter 헬퍼를 통하지 않고 직접 computeIfAbsent — 카디널리티 WARN 회피 의도.
            attemptCounters.computeIfAbsent(tag) { buildAttemptCounter(it) }
            acquiredCounters.computeIfAbsent(tag) { buildAcquiredCounter(it) }
            acquireTimers.computeIfAbsent(tag) { buildAcquireTimer(it) }
            executionTimers.computeIfAbsent(tag) { buildExecutionTimer(it) }
            activeGauges.computeIfAbsent(tag) { buildActiveGauge(it) }
            // notAcquiredCounters / failedCounters : reason/exception 카디널리티 미지 → lazy 유지.
        }
    }

    /** 지정한 lock 이름의 meter를 유휴 상태에서 registry와 내부 cache에서 제거합니다. */
    fun deregisterMetricsFor(vararg lockNames: String) {
        lockNames.forEach { name ->
            val tag = sanitizeLockName(name)
            if (!canRemove(tag, name)) {
                return@forEach
            }
            attemptCounters.remove(tag)?.let { registry.remove(it) }
            acquiredCounters.remove(tag)?.let { registry.remove(it) }
            acquireTimers.remove(tag)?.let { registry.remove(it) }
            executionTimers.remove(tag)?.let { registry.remove(it) }
            activeGauges.remove(tag)?.also {
                registry.find(MicrometerNames.METER_ACTIVE)
                    .tag(MicrometerNames.TAG_LOCK_NAME, tag)
                    .gauge()
                    ?.let { gauge -> registry.remove(gauge) }
            }
            notAcquiredCounters.keys.filter { it.first == tag }.forEach { key ->
                notAcquiredCounters.remove(key)?.let { registry.remove(it) }
            }
            failedCounters.keys.filter { it.first == tag }.forEach { key ->
                failedCounters.remove(key)?.let { registry.remove(it) }
            }
        }
    }

    // 카디널리티 WARN 은 여기에서만 발생 — registerMetricsFor 는 의도적으로 우회한다.
    private fun attemptCounter(name: String): Counter =
        attemptCounters.computeIfAbsent(name) {
            log.warn {
                "leader-micrometer: new lock.name='$name' registered — " +
                    "beware tag cardinality if using dynamic SpEL"
            }
            buildAttemptCounter(name)
        }

    private fun acquiredCounter(name: String): Counter =
        acquiredCounters.computeIfAbsent(name) { buildAcquiredCounter(it) }

    private fun acquireTimer(name: String): Timer =
        acquireTimers.computeIfAbsent(name) { buildAcquireTimer(it) }

    private fun notAcquiredCounter(name: String, reason: SkipReason): Counter =
        notAcquiredCounters.computeIfAbsent(name to reason) { (n, r) ->
            Counter.builder(MicrometerNames.METER_NOT_ACQUIRED)
                .tag(MicrometerNames.TAG_LOCK_NAME, n)
                .tag(MicrometerNames.TAG_REASON, r.name)
                .register(registry)
        }

    private fun executionTimer(name: String): Timer =
        executionTimers.computeIfAbsent(name) { buildExecutionTimer(it) }

    private fun failedCounter(name: String, exceptionName: String): Counter =
        failedCounters.computeIfAbsent(name to exceptionName) { (n, e) ->
            Counter.builder(MicrometerNames.METER_TASK_FAILED)
                .tag(MicrometerNames.TAG_LOCK_NAME, n)
                .tag(MicrometerNames.TAG_EXCEPTION, e)
                .register(registry)
        }

    private fun activeGauge(name: String): AtomicInteger =
        activeGauges.computeIfAbsent(name) { buildActiveGauge(it) }

    private fun sanitizeLockName(name: String): String =
        tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, name)

    private fun canRemove(tag: String, rawName: String): Boolean {
        if ((activeGauges[tag]?.get() ?: 0) > 0) {
            return false
        }
        var removable = false
        explicitRegistrations.compute(tag) { _, registeredNames ->
            if (registeredNames == null) {
                removable = removeUnregisteredMeters
                null
            } else {
                registeredNames.remove(rawName)
                if (registeredNames.isEmpty()) {
                    removable = true
                    null
                } else {
                    removable = false
                    registeredNames
                }
            }
        }
        return removable
    }

    private fun buildAttemptCounter(name: String): Counter =
        Counter.builder(MicrometerNames.METER_ATTEMPTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)

    private fun buildAcquiredCounter(name: String): Counter =
        Counter.builder(MicrometerNames.METER_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)

    private fun buildAcquireTimer(name: String): Timer =
        Timer.builder(MicrometerNames.METER_ACQUIRE_DURATION)
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)

    private fun buildExecutionTimer(name: String): Timer =
        Timer.builder(MicrometerNames.METER_EXECUTION_DURATION)
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)

    private fun buildActiveGauge(name: String): AtomicInteger {
        val counter = AtomicInteger(0)
        Gauge.builder(MicrometerNames.METER_ACTIVE, counter) { it.get().toDouble() }
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)
        return counter
    }
}

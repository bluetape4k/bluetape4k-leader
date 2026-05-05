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
 * [LeaderAopMetricsRecorder] Micrometer 기반 구현체.
 *
 * leader-aop 의 6 개 콜백을 Micrometer Counter/Timer/Gauge 로 매핑하여 Prometheus, Datadog 등
 * 임의의 백엔드로 export 할 수 있도록 한다.
 *
 * ## 메터 카탈로그
 *
 * | 메터 이름 (`MicrometerNames.*`)              | 타입    | 콜백              |
 * |--------------------------------------------|--------|------------------|
 * | `leader.aop.attempts`                      | Counter | onLockAttempt    |
 * | `leader.aop.acquired`                      | Counter | onLockAcquired   |
 * | `leader.aop.lock.not.acquired`             | Counter | onLockNotAcquired |
 * | `leader.aop.execution.duration`            | Timer   | onTaskFinished   |
 * | `leader.aop.task.failed`                   | Counter | onTaskFailed     |
 * | `leader.aop.active`                        | Gauge   | onTaskStarted/Finished/Failed |
 *
 * 모든 메터는 `lock.name` 태그를 공유하며, `not.acquired` 는 `reason`, `task.failed` 는 `exception` 태그를 추가로 가진다.
 * 메터/태그 이름 상수는 [MicrometerNames] 참조.
 *
 * ## 카디널리티 경고
 *
 * `lock.name` 은 [io.bluetape4k.leader.spring.LeaderElection.name] SpEL 결과 그대로 태그가 된다.
 * 동적 SpEL (`'tenant-' + #tenantId`) 을 그대로 노출하면 메터 인스턴스가 무한 증식해 백엔드를 고사시킬 수 있다.
 * 첫 등록 시 한 번 WARN 로그를 남기지만 — 정적 화이트리스트 사용을 권장한다.
 *
 * ## 멀티 인스턴스 환경
 *
 * `leader.aop.active` Gauge 는 **JVM-local** 값이다. Prometheus 에서 클러스터 전체 leader 수를 보려면
 * `sum` 이 아니라 `max by (lock_name) (leader_aop_active)` 를 사용해야 한다 — leader 가 1 명이어도
 * 모든 인스턴스 합산 시 N 으로 보이는 함정을 피한다.
 *
 * ## 사전/사후 등록
 *
 * - [registerMetricsFor] : 정적 lock 이름들을 부트 시점에 등록 → 첫 호출 전에도 dashboard 에 0 이 표시됨.
 *   `SmartInitializingSingleton` 과 함께 사용 권장.
 * - [deregisterMetricsFor] : 동적 SpEL 로 생성된 더 이상 사용하지 않는 lock 이름의 메터를 회수 →
 *   `MeterRegistry` 메모리 누수 방지.
 *
 * @see MicrometerNames
 * @see LeaderAopMetricsRecorder
 */
class MicrometerLeaderAopMetricsRecorder(
    private val registry: MeterRegistry,
): LeaderAopMetricsRecorder {

    companion object: KLogging()

    private val attemptCounters = ConcurrentHashMap<String, Counter>()
    private val acquiredCounters = ConcurrentHashMap<String, Counter>()
    private val notAcquiredCounters = ConcurrentHashMap<NotAcquiredKey, Counter>()
    private val executionTimers = ConcurrentHashMap<String, Timer>()
    private val failedCounters = ConcurrentHashMap<FailedKey, Counter>()
    private val activeGauges = ConcurrentHashMap<String, AtomicInteger>()

    override fun onLockAttempt(name: String, options: LeaderElectionOptions) {
        attemptCounter(name).increment()
    }

    override fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {
        // acquireElapsed → v2 후보 (leader.aop.acquire.duration)
        acquiredCounter(name).increment()
    }

    override fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {
        notAcquiredCounter(name, reason).increment()
    }

    override fun onTaskStarted(name: String) {
        activeGauge(name).incrementAndGet()
    }

    override fun onTaskFinished(name: String, executionTime: Duration) {
        try {
            executionTimer(name).record(executionTime.toJavaDuration())
        } finally {
            activeGauge(name).decrementAndGet()
        }
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        try {
            failedCounter(name, throwable::class.simpleName ?: MicrometerNames.UNKNOWN_EXCEPTION).increment()
        } finally {
            // onTaskFailed 는 onTaskStarted 없이도 호출될 수 있다 (backend error path).
            // decrementAndGet 전에 값이 0 보다 큰지 확인해 음수 방지.
            activeGauge(name).updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    /**
     * 정적으로 알려진 lock 이름들의 메터를 미리 등록한다.
     *
     * 첫 콜백 발생 전에도 dashboard 에 0 값이 표시되도록 하기 위함이다 — Prometheus 의 `rate()` 함수는
     * 시리즈가 존재하지 않는 구간을 NaN 으로 표시하므로 사전 등록이 운영상 유리하다.
     *
     * **멱등 (idempotent)** : 이미 등록된 lock 이름은 재등록되지 않는다 (`computeIfAbsent` 사용).
     *
     * **사전 등록 대상** :
     * - `leader.aop.attempts` Counter
     * - `leader.aop.acquired` Counter
     * - `leader.aop.execution.duration` Timer
     * - `leader.aop.active` Gauge
     *
     * **사전 등록 제외** :
     * - `leader.aop.lock.not.acquired` — `reason` 태그 카디널리티가 미지 (호출 전엔 어떤 reason 이 발생할지 알 수 없음)
     * - `leader.aop.task.failed` — `exception` 태그 카디널리티가 미지 (예외 클래스 사전 예측 불가)
     *
     * 이 함수는 카디널리티 WARN 로그를 발생시키지 않는다 — 사전 등록된 정적 lock 이름이 WARN 을 유발하면
     * 운영 시 발생하는 진짜 동적 SpEL WARN 신호의 의미가 퇴색되기 때문이다.
     *
     * `SmartInitializingSingleton` 또는 `ApplicationReadyEvent` 리스너에서 호출하기를 권장한다.
     */
    fun registerMetricsFor(vararg lockNames: String) {
        lockNames.forEach { name ->
            // attemptCounter 헬퍼를 통하지 않고 직접 computeIfAbsent — 카디널리티 WARN 회피 의도.
            attemptCounters.computeIfAbsent(name) { buildAttemptCounter(it) }
            acquiredCounters.computeIfAbsent(name) { buildAcquiredCounter(it) }
            executionTimers.computeIfAbsent(name) { buildExecutionTimer(it) }
            activeGauges.computeIfAbsent(name) { buildActiveGauge(it) }
            // notAcquiredCounters / failedCounters : reason/exception 카디널리티 미지 → lazy 유지.
        }
    }

    /**
     * 더 이상 사용하지 않는 lock 이름의 메터를 [MeterRegistry] 에서 제거한다.
     *
     * 동적 SpEL (`'tenant-' + #tenantId`) 로 생성된 lock 이름이나 은퇴한 잡의 lock 이름을 정리해
     * `MeterRegistry` 의 메모리 누수와 시계열 백엔드의 카디널리티 폭증을 방지한다.
     *
     * **정리 대상** : 해당 `lockName` 으로 등록된 모든 6 종 메터 (Counter/Timer/Gauge) +
     * `notAcquired` 의 모든 reason 변종, `failed` 의 모든 exception 변종.
     *
     * 호출 후 동일 lock 이름으로 콜백이 다시 발생하면 메터는 자동 재생성된다 (이때는 카디널리티 WARN 발생).
     */
    fun deregisterMetricsFor(vararg lockNames: String) {
        lockNames.forEach { name ->
            attemptCounters.remove(name)?.let { registry.remove(it) }
            acquiredCounters.remove(name)?.let { registry.remove(it) }
            executionTimers.remove(name)?.let { registry.remove(it) }
            activeGauges.remove(name)?.also {
                registry.find(MicrometerNames.METER_ACTIVE)
                    .tag(MicrometerNames.TAG_LOCK_NAME, name)
                    .gauge()
                    ?.let { gauge -> registry.remove(gauge) }
            }
            notAcquiredCounters.keys.filter { it.first == name }.forEach { key ->
                notAcquiredCounters.remove(key)?.let { registry.remove(it) }
            }
            failedCounters.keys.filter { it.first == name }.forEach { key ->
                failedCounters.remove(key)?.let { registry.remove(it) }
            }
        }
    }

    // 카디널리티 WARN 은 여기에서만 발생 — registerMetricsFor 는 의도적으로 우회한다.
    private fun attemptCounter(name: String): Counter =
        attemptCounters.computeIfAbsent(name) {
            log.warn { "leader-micrometer: new lock.name='$name' registered — beware tag cardinality if using dynamic SpEL" }
            buildAttemptCounter(name)
        }

    private fun acquiredCounter(name: String): Counter =
        acquiredCounters.computeIfAbsent(name) { buildAcquiredCounter(it) }

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

    private fun buildAttemptCounter(name: String): Counter =
        Counter.builder(MicrometerNames.METER_ATTEMPTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, name)
            .register(registry)

    private fun buildAcquiredCounter(name: String): Counter =
        Counter.builder(MicrometerNames.METER_ACQUIRED)
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

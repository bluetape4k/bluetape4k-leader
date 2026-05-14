package io.bluetape4k.leader

import io.bluetape4k.leader.ExtendOutcome.BackendError
import io.bluetape4k.leader.ExtendOutcome.Extended
import io.bluetape4k.leader.ExtendOutcome.NotHeld
import io.bluetape4k.leader.ExtendOutcome.WrongThread
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.CoreBackendErrorClassifier
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 리더 lease를 백엔드 소유권 조건으로 주기 연장하는 공통 watchdog helper입니다.
 *
 * ## 계약
 * - [extend]는 반드시 현재 owner token/thread/instance 조건을 확인해야 합니다.
 * - [extend]가 `false`를 반환하거나 예외를 던지면 watchdog은 반복을 중단합니다.
 * - 호출자는 action 종료/예외/취소 release path에서 반환된 [AutoCloseable]을 닫아야 합니다.
 *
 * ## ExtendDelegate 기반 권장 사용 (R2 watchdog skip)
 * ```kotlin
 * val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
 * try {
 *     action()
 * } finally {
 *     watchdog.close()
 * }
 * ```
 *
 * ## 기존 Boolean 람다 사용 (Deprecated)
 * ```kotlin
 * val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime) {
 *     lock.extend(options.leaseTime)
 * }
 * try {
 *     action()
 * } finally {
 *     watchdog.close()
 * }
 * ```
 */
object LeaderLeaseAutoExtender : KLogging() {

    private val threadSeq = AtomicInteger()

    /**
     * Default watchdog scheduler thread-pool size.
     *
     * Uses `availableProcessors().coerceAtLeast(2)` so single-CPU environments still get 2 threads.
     * Override at runtime with [configure].
     */
    internal val DEFAULT_WATCHDOG_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    @Volatile
    private var configuredThreadCount: Int = DEFAULT_WATCHDOG_THREADS

    @Volatile
    private var asyncExtendEnabled: Boolean = false

    @Volatile
    private var scheduler: ScheduledThreadPoolExecutor = newScheduler()

    /**
     * Returns the current configured watchdog thread-pool size.
     */
    fun watchdogThreadCount(): Int = configuredThreadCount

    /**
     * Configures the shared watchdog scheduler.
     *
     * ## Behavior / Contract
     * - `watchdogThreads` is applied immediately to any running scheduler via
     *   [ScheduledThreadPoolExecutor.setCorePoolSize] and also stored for the next [restart].
     * - `asyncExtend` takes effect immediately for new [start] calls. When `true`, each watchdog tick
     *   dispatches the backend extend call onto a virtual thread, preventing slow backends from blocking
     *   the shared scheduler. An `extendInFlight` guard ensures at most one async extend runs per watchdog.
     *
     * @param watchdogThreads thread-pool size for the scheduler (>= 1). Defaults to current value.
     * @param asyncExtend when `true`, extend calls are dispatched on virtual threads.
     */
    fun configure(
        watchdogThreads: Int = configuredThreadCount,
        asyncExtend: Boolean = asyncExtendEnabled,
    ) {
        require(watchdogThreads >= 1) { "watchdogThreads must be >= 1, got $watchdogThreads" }
        configuredThreadCount = watchdogThreads
        asyncExtendEnabled = asyncExtend
        // Apply thread count to the running scheduler immediately.
        // ScheduledThreadPoolExecutor supports live setCorePoolSize() without disrupting queued tasks.
        val current = scheduler
        if (!current.isShutdown && current.corePoolSize != watchdogThreads) {
            current.corePoolSize = watchdogThreads
        }
    }

    /**
     * Shuts down the shared watchdog scheduler, waiting up to 5 seconds for in-flight ticks to finish.
     * Safe to call multiple times. After shutdown, calls to [start] will throw [RejectedExecutionException]
     * from the scheduler until [restart] is invoked. The tick-body [RejectedExecutionException] catch
     * protects ticks whose delegate submits further work to a separately shut-down executor.
     *
     * ## Concurrency note
     * The scheduler reference is captured once into a local variable before any blocking calls so that
     * a concurrent [restart] cannot swap in a fresh executor between [shutdown][ScheduledThreadPoolExecutor.shutdown]
     * and [awaitTermination][ScheduledThreadPoolExecutor.awaitTermination] / [shutdownNow][ScheduledThreadPoolExecutor.shutdownNow].
     */
    fun shutdown() {
        val current = scheduler
        current.shutdown()
        if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
            current.shutdownNow()
        }
    }

    /**
     * Replaces the scheduler with a fresh instance if the current one has been shut down.
     * No-op when the scheduler is still running.
     */
    fun restart() {
        if (!scheduler.isShutdown) return
        scheduler = newScheduler()
    }

    /** Returns `true` if the shared watchdog scheduler has been shut down. */
    fun isShutdown(): Boolean = scheduler.isShutdown

    private fun newScheduler(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(configuredThreadCount) { runnable ->
            Thread(runnable, "bluetape4k-leader-lease-watchdog-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.apply { removeOnCancelPolicy = true }

    /**
     * [enabled]가 true이면 [ExtendDelegate] 를 이용한 lease 연장 watchdog을 시작합니다.
     *
     * ## R2 watchdog skip
     * tick 마다 `delegate.lastExtendDeadline.get()` 를 읽어,
     * `now + cadence < lastExtendDeadline` 이면 backend extend 호출을 skip 합니다.
     * User 가 `LockExtender.extendActiveLock(60s)` 를 호출한 직후 watchdog 이 lease 를
     * 작은 값으로 silently 축소하는 split-brain 을 차단합니다.
     *
     * ## 종료 조건
     * - [ExtendOutcome.NotHeld] / [ExtendOutcome.WrongThread] → watchdog 중단 (소유권 상실)
     * - [ExtendOutcome.BackendError] (TRANSIENT) → WARN log + 계속 (재시도)
     * - [ExtendOutcome.BackendError] (NON_TRANSIENT / FATAL) → WARN log + watchdog 중단
     * - [AutoCloseable.close] 호출 → 중단
     *
     * @param enabled `false` 이면 no-op closeable 반환
     * @param leaseTime backend lease 시간 — cadence = leaseTime / 3 (최소 [MIN_RENEWAL_PERIOD])
     * @param delegate backend extend SPI — [ExtendDelegate.extend] + [ExtendDelegate.lastExtendDeadline]
     * @param classifier backend-specific [BackendErrorClassifier]. `null` (default) 이면
     * [CoreBackendErrorClassifier] (JDK / SQL only) 사용 — backend 별 transient 분류 누락 가능.
     * 각 backend module 은 자신의 classifier ([io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier]
     * 등) 를 [CompositeBackendErrorClassifier] 로 wrap 하여 전달 권장.
     */
    fun start(
        enabled: Boolean,
        leaseTime: Duration,
        delegate: ExtendDelegate,
        classifier: BackendErrorClassifier? = null,
    ): AutoCloseable {
        val errorClassifier: BackendErrorClassifier = classifier ?: CoreBackendErrorClassifier
        if (!enabled) {
            return NoopCloseable
        }

        val closed = AtomicBoolean(false)
        val cadence = renewalPeriod(leaseTime)
        val futureRef = AtomicReference<ScheduledFuture<*>?>(null)
        // Capture async mode at start() call time — subsequent configure() calls don't affect running watchdogs.
        val capturedAsyncExtend = asyncExtendEnabled
        val extendInFlight: AtomicBoolean? = if (capturedAsyncExtend) AtomicBoolean(false) else null

        val doTick: () -> Unit = doTick@{
            if (closed.get()) {
                futureRef.get()?.cancel(false)
                return@doTick
            }

            // R2 mitigation: user 가 explicit extend 를 호출했으면 watchdog skip
            val deadline = delegate.lastExtendDeadline.get()
            if (deadline != null && Instant.now().plusMillis(cadence.inWholeMilliseconds).isBefore(deadline)) {
                return@doTick
            }

            val outcome = try {
                delegate.extend(leaseTime)
            } catch (ex: RejectedExecutionException) {
                log.warn(ex) { "Watchdog tick rejected — scheduler shut down. delegateId=${delegate.hashCode()}" }
                futureRef.get()?.cancel(false)
                return@doTick
            } catch (ex: Exception) {
                log.warn(ex) { "leader.lease.auto-extend.failed" }
                BackendError(ex)
            }

            when (outcome) {
                is Extended -> { /* 정상 연장 — 계속 */ }
                is NotHeld, is WrongThread -> {
                    log.warn { "leader.lease.auto-extend.stopped reason=$outcome" }
                    if (closed.compareAndSet(false, true)) {
                        futureRef.get()?.cancel(false)
                    }
                }
                is BackendError -> {
                    val kind = errorClassifier.classify(outcome.cause)
                        ?: BackendErrorKind.NON_TRANSIENT
                    when (kind) {
                        BackendErrorKind.TRANSIENT -> {
                            log.warn(outcome.cause) { "leader.lease.auto-extend.transient-error — retrying. cause=${outcome.cause.message}" }
                        }
                        BackendErrorKind.NON_TRANSIENT, BackendErrorKind.FATAL -> {
                            log.warn(outcome.cause) { "leader.lease.auto-extend.stopped reason=BACKEND_ERROR kind=$kind cause=${outcome.cause.message}" }
                            if (closed.compareAndSet(false, true)) {
                                futureRef.get()?.cancel(false)
                            }
                        }
                    }
                }
            }
        }

        // When async mode is enabled: dispatch each tick onto a virtual thread so that a slow backend
        // cannot block the shared scheduler. The extendInFlight guard prevents overlapping extends.
        val tickRunnable: Runnable = if (capturedAsyncExtend && extendInFlight != null) {
            Runnable {
                if (extendInFlight.compareAndSet(false, true)) {
                    Thread.ofVirtual()
                        .name("leader-lease-extend-${threadSeq.incrementAndGet()}")
                        .start {
                            try { doTick() } finally { extendInFlight.set(false) }
                        }
                }
            }
        } else {
            Runnable { doTick() }
        }

        val future = try {
            scheduler.scheduleWithFixedDelay(
                tickRunnable,
                cadence.inWholeMilliseconds,
                cadence.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (ex: RejectedExecutionException) {
            log.warn(ex) { "Watchdog scheduling rejected — scheduler shut down. Returning no-op." }
            return NoopCloseable
        }
        futureRef.set(future)

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
            }
        }
    }

    /**
     * [enabled]가 true이면 lease 연장 watchdog을 시작하고, false이면 no-op closeable을 반환합니다.
     *
     * @deprecated [ExtendDelegate] 를 받는 [start] 사용 권장.
     * R2 watchdog skip semantics (`lastExtendDeadline` 검사) 가 없음.
     * T7~T13 에서 각 backend elector 가 [ExtendDelegate] 로 migration 후 이 overload 제거 예정.
     */
    @Deprecated(
        message = "Use start(enabled, leaseTime, delegate: ExtendDelegate) for R2 watchdog skip semantics. " +
            "This overload will be removed after T7–T13 backend migration.",
        replaceWith = ReplaceWith("start(enabled, leaseTime, delegate)"),
    )
    fun start(
        enabled: Boolean,
        leaseTime: Duration,
        extend: () -> Boolean,
    ): AutoCloseable {
        if (!enabled) {
            return NoopCloseable
        }

        val closed = AtomicBoolean(false)
        val period = renewalPeriod(leaseTime)
        val futureRef = AtomicReference<ScheduledFuture<*>?>(null)

        val future = try {
            scheduler.scheduleWithFixedDelay(
                {
                    if (closed.get()) {
                        futureRef.get()?.cancel(false)
                        return@scheduleWithFixedDelay
                    }

                    val extendResult = runCatching { extend() }
                        .onFailure { ex ->
                            if (ex is RejectedExecutionException) {
                                log.warn(ex) { "Watchdog tick rejected — scheduler shut down. extenderId=${extend.hashCode()}" }
                                futureRef.get()?.cancel(false)
                                return@scheduleWithFixedDelay
                            }
                            log.warn(ex) { "leader.lease.auto-extend.failed" }
                        }
                    val extended = extendResult.getOrDefault(false)

                    if (!extended && closed.compareAndSet(false, true)) {
                        log.warn { "leader.lease.auto-extend.stopped reason=NOT_OWNER" }
                        futureRef.get()?.cancel(false)
                    }
                },
                period.inWholeMilliseconds,
                period.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (ex: RejectedExecutionException) {
            log.warn(ex) { "Watchdog scheduling rejected — scheduler shut down. Returning no-op." }
            return NoopCloseable
        }
        futureRef.set(future)

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
            }
        }
    }

    /**
     * [leaseTime] 기준 watchdog 반복 주기를 계산합니다.
     */
    fun renewalPeriod(leaseTime: Duration): Duration {
        val third = leaseTime / 3
        return if (third > MIN_RENEWAL_PERIOD) third else MIN_RENEWAL_PERIOD
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private val MIN_RENEWAL_PERIOD = 25.milliseconds
}

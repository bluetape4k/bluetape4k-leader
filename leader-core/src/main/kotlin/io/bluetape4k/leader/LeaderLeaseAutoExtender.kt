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
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Common watchdog helper that periodically extends a leader lease under backend ownership conditions.
 *
 * ## Contract
 * - [extend] must verify the current owner token/thread/instance condition.
 * - If [extend] returns `false` or throws an exception, the watchdog stops iterating.
 * - The caller must close the returned [AutoCloseable] in the action's termination/exception/cancellation release path.
 *
 * ## Recommended usage with ExtendDelegate (R2 watchdog skip)
 * ```kotlin
 * val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
 * try {
 *     action()
 * } finally {
 *     watchdog.close()
 * }
 * ```
 *
 * ## Legacy Boolean lambda usage (Deprecated)
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
     * Starts a lease-extension watchdog using [ExtendDelegate] when [enabled] is true.
     *
     * ## R2 watchdog skip
     * On each tick, reads `delegate.lastExtendDeadline.get()`. If
     * `now + cadence < lastExtendDeadline`, the backend extend call is skipped.
     * This prevents a split-brain where the watchdog silently shrinks the lease to a smaller
     * value immediately after the user calls `LockExtender.extendActiveLock(60s)`.
     *
     * ## Termination conditions
     * - [ExtendOutcome.NotHeld] / [ExtendOutcome.WrongThread] → watchdog stops (ownership lost)
     * - [ExtendOutcome.BackendError] (TRANSIENT) → WARN log + continue (retry)
     * - [ExtendOutcome.BackendError] (NON_TRANSIENT / FATAL) → WARN log + watchdog stops
     * - [AutoCloseable.close] called → stops
     *
     * @param enabled returns a no-op closeable when `false`
     * @param leaseTime backend lease duration — cadence = leaseTime / 3 (minimum [MIN_RENEWAL_PERIOD])
     * @param delegate backend extend SPI — [ExtendDelegate.extend] + [ExtendDelegate.lastExtendDeadline]
     * @param classifier backend-specific [BackendErrorClassifier]. When `null` (default),
     * [CoreBackendErrorClassifier] (JDK / SQL only) is used — may miss transient classifications for
     * specific backends. Each backend module should wrap its own classifier (e.g.
     * [io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier]) with
     * [CompositeBackendErrorClassifier] and pass it here.
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

            // R2 mitigation: skip watchdog if the user already called an explicit extend
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
                is Extended -> { /* Extended successfully — continue */ }
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
     * Starts a lease-extension watchdog for a coroutine-native [SuspendExtendDelegate].
     *
     * The existing scheduler owns cadence so [configure] thread-count behavior still applies.
     * Each due tick is dispatched into a private coroutine scope and calls [SuspendExtendDelegate.extendSuspend]
     * directly. This overload must not use `runBlocking`.
     * `asyncExtend` does not apply because the backend call already runs outside the scheduler thread.
     *
     * `close()` stops future scheduling immediately. If a suspend extend is already in flight, it is allowed
     * to finish the atomic backend operation; the private scope is cancelled after that tick completes.
     */
    fun start(
        enabled: Boolean,
        leaseTime: Duration,
        delegate: SuspendExtendDelegate,
        classifier: BackendErrorClassifier? = null,
    ): AutoCloseable {
        val errorClassifier: BackendErrorClassifier = classifier ?: CoreBackendErrorClassifier
        if (!enabled) {
            return NoopCloseable
        }

        val closed = AtomicBoolean(false)
        val cadence = renewalPeriod(leaseTime)
        val futureRef = AtomicReference<ScheduledFuture<*>?>(null)
        val extendInFlight = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun cancelScopeIfIdle() {
            if (closed.get() && !extendInFlight.get()) {
                scope.cancel()
            }
        }

        suspend fun doSuspendTick() {
            if (closed.get()) {
                futureRef.get()?.cancel(false)
                return
            }

            val deadline = delegate.lastExtendDeadline.get()
            if (deadline != null && Instant.now().plusMillis(cadence.inWholeMilliseconds).isBefore(deadline)) {
                return
            }

            val outcome = try {
                delegate.extendSuspend(leaseTime)
            } catch (ex: RejectedExecutionException) {
                log.warn(ex) { "Suspend watchdog tick rejected — scheduler shut down. delegateId=${delegate.hashCode()}" }
                futureRef.get()?.cancel(false)
                return
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.warn(ex) { "leader.lease.auto-extend.suspend.failed" }
                BackendError(ex)
            }

            handleOutcome(outcome, errorClassifier, closed, futureRef)
        }

        val tickRunnable = Runnable {
            if (closed.get()) {
                futureRef.get()?.cancel(false)
                cancelScopeIfIdle()
                return@Runnable
            }
            if (extendInFlight.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        doSuspendTick()
                    } finally {
                        extendInFlight.set(false)
                        cancelScopeIfIdle()
                    }
                }
            }
        }

        val future = try {
            scheduler.scheduleWithFixedDelay(
                tickRunnable,
                cadence.inWholeMilliseconds,
                cadence.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (ex: RejectedExecutionException) {
            log.warn(ex) { "Suspend watchdog scheduling rejected — scheduler shut down. Returning no-op." }
            scope.cancel()
            return NoopCloseable
        }
        futureRef.set(future)

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
                cancelScopeIfIdle()
            }
        }
    }

    /**
     * Computes the watchdog repetition period based on [leaseTime].
     */
    fun renewalPeriod(leaseTime: Duration): Duration {
        val third = leaseTime / 3
        return if (third > MIN_RENEWAL_PERIOD) third else MIN_RENEWAL_PERIOD
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private val MIN_RENEWAL_PERIOD = 25.milliseconds

    private fun handleOutcome(
        outcome: ExtendOutcome,
        errorClassifier: BackendErrorClassifier,
        closed: AtomicBoolean,
        futureRef: AtomicReference<ScheduledFuture<*>?>,
    ) {
        when (outcome) {
            is Extended -> { /* Extended successfully — continue */ }
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
}

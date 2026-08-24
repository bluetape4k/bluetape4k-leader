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
import io.bluetape4k.support.requirePositiveNumber

private fun publishLeaderLeaseWatchdogEvent(
    observing: Boolean,
    execution: LeaderLeaseExtensionExecution,
    outcome: ExtendOutcome,
    elapsedNanos: Long,
) {
    if (!observing) return
    LeaderLeaseExtensionObservers.publish(
        LeaderLeaseExtensionEvent(
            source = LeaderLeaseExtensionSource.WATCHDOG,
            execution = execution,
            outcome = outcome,
            elapsedNanos = elapsedNanos,
            context = null,
        ),
    )
}

/**
 * `LeaderLeaseAutoExtender` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object LeaderLeaseAutoExtender : KLogging() {

    private val threadSeq = AtomicInteger()

    /**
     * `DEFAULT_WATCHDOG_THREADS` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    internal val DEFAULT_WATCHDOG_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    @Volatile
    private var configuredThreadCount: Int = DEFAULT_WATCHDOG_THREADS

    @Volatile
    private var asyncExtendEnabled: Boolean = false

    @Volatile
    private var scheduler: ScheduledThreadPoolExecutor = newScheduler()

    /**
     * `watchdogThreadCount` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun watchdogThreadCount(): Int = configuredThreadCount

    /**
     * `configure` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param watchdogThreads `watchdogThreads` 호출 또는 상태 계산에 필요한 값입니다.
     * @param asyncExtend `asyncExtend` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun configure(
        watchdogThreads: Int = configuredThreadCount,
        asyncExtend: Boolean = asyncExtendEnabled,
    ) {
        watchdogThreads.requirePositiveNumber("watchdogThreads")
        configuredThreadCount = watchdogThreads
        asyncExtendEnabled = asyncExtend
        // 실행 중인 scheduler에 thread count를 즉시 반영합니다.
        // ScheduledThreadPoolExecutor는 queued task를 깨지 않고 live setCorePoolSize()를 지원합니다.
        val current = scheduler
        if (!current.isShutdown && current.corePoolSize != watchdogThreads) {
            current.corePoolSize = watchdogThreads
        }
    }

    /**
     * `shutdown` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun shutdown() {
        val current = scheduler
        current.shutdown()
        if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
            current.shutdownNow()
        }
    }

    /**
     * `restart` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun restart() {
        if (!scheduler.isShutdown) return
        scheduler = newScheduler()
    }

    /**
     * `isShutdown` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun isShutdown(): Boolean = scheduler.isShutdown

    private fun newScheduler(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(configuredThreadCount) { runnable ->
            Thread(runnable, "bluetape4k-leader-lease-watchdog-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.apply { removeOnCancelPolicy = true }

    /**
     * `start` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param enabled `enabled` 호출 또는 상태 계산에 필요한 값입니다.
     * @param leaseTime leadership을 보유할 수 있는 lease TTL입니다.
     * @param delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
     * @param classifier `classifier` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
        // start() 호출 시점의 async mode를 캡처합니다. 이후 configure() 호출은 실행 중인 watchdog에 영향을 주지 않습니다.
        val capturedAsyncExtend = asyncExtendEnabled
        val extendInFlight = AtomicBoolean(false)
        val admission = LeaderLeaseWatchdogAdmission.current()

        val doTick: () -> Unit = doTick@{
            if (closed.get()) {
                futureRef.get()?.cancel(false)
                return@doTick
            }

            // R2 완화: 사용자가 이미 명시적으로 extend를 호출했다면 watchdog tick을 건너뜁니다.
            val deadline = delegate.lastExtendDeadline.get()
            if (deadline != null && Instant.now().plusMillis(cadence.inWholeMilliseconds).isBefore(deadline)) {
                return@doTick
            }

            val observing = LeaderLeaseExtensionObservers.hasObservers()
            val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
            var delegateRejected = false
            var delegateElapsedNanos = 0L
            val watchdogReservation = admission?.invoke()
            if (admission != null && watchdogReservation == null) {
                publishLeaderLeaseWatchdogEvent(
                    observing,
                    LeaderLeaseExtensionExecution.BLOCKING,
                    ExtendOutcome.Rejected,
                    0L,
                )
                return@doTick
            }
            val outcome = try {
                delegate.extend(leaseTime).also {
                    delegateElapsedNanos = if (observing) {
                        (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                }
            } catch (ex: CancellationException) {
                closed.set(true)
                futureRef.get()?.cancel(false)
                throw ex
            } catch (ex: RejectedExecutionException) {
                delegateElapsedNanos = if (observing) {
                    (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                } else {
                    0L
                }
                log.warn(ex) { "Watchdog delegate rejected extension. delegateId=${delegate.hashCode()}" }
                delegateRejected = true
                BackendError(ex)
            } catch (ex: Exception) {
                delegateElapsedNanos = if (observing) {
                    (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                } else {
                    0L
                }
                log.warn(ex) { "leader.lease.auto-extend.failed" }
                BackendError(ex)
            } finally {
                watchdogReservation?.close()
            }

            publishLeaderLeaseWatchdogEvent(
                observing,
                LeaderLeaseExtensionExecution.BLOCKING,
                outcome,
                delegateElapsedNanos,
            )
            handleOutcome(outcome, errorClassifier, closed, futureRef, forceStop = delegateRejected)
        }

        // async mode에서는 각 tick을 virtual thread로 dispatch해 느린 backend가 shared scheduler를 막지 않게 합니다.
        // extendInFlight guard는 extend 호출이 겹치지 않도록 막습니다.
        val tickRunnable: Runnable = if (capturedAsyncExtend) {
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
            Runnable {
                if (extendInFlight.compareAndSet(false, true)) {
                    try {
                        doTick()
                    } finally {
                        extendInFlight.set(false)
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
            log.warn(ex) { "Watchdog scheduling rejected — scheduler shut down. Returning no-op." }
            return NoopCloseable
        }
        futureRef.set(future)

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
                waitForInFlightExtend(extendInFlight)
            }
        }
    }

    /**
     * `start` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param enabled `enabled` 호출 또는 상태 계산에 필요한 값입니다.
     * @param leaseTime leadership을 보유할 수 있는 lease TTL입니다.
     * @param delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
     * @param classifier `classifier` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
        val admission = LeaderLeaseWatchdogAdmission.current()

        fun cancelScopeIfIdle() {
            if (closed.get() && !extendInFlight.get()) {
                scope.cancel()
            }
        }

        @Suppress("LongMethod", "ReturnCount")
        suspend fun doSuspendTick() {
            if (closed.get()) {
                futureRef.get()?.cancel(false)
                return
            }

            val deadline = delegate.lastExtendDeadline.get()
            if (deadline != null && Instant.now().plusMillis(cadence.inWholeMilliseconds).isBefore(deadline)) {
                return
            }

            val observing = LeaderLeaseExtensionObservers.hasObservers()
            val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
            var delegateRejected = false
            var delegateElapsedNanos = 0L
            val watchdogReservation = admission?.invoke()
            if (admission != null && watchdogReservation == null) {
                publishLeaderLeaseWatchdogEvent(
                    observing,
                    LeaderLeaseExtensionExecution.SUSPEND,
                    ExtendOutcome.Rejected,
                    0L,
                )
                return
            }
            val outcome = try {
                delegate.extendSuspend(leaseTime).also {
                    delegateElapsedNanos = if (observing) {
                        (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: RejectedExecutionException) {
                delegateElapsedNanos = if (observing) {
                    (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                } else {
                    0L
                }
                log.warn(ex) { "Suspend watchdog delegate rejected extension. delegateId=${delegate.hashCode()}" }
                delegateRejected = true
                BackendError(ex)
            } catch (ex: Exception) {
                delegateElapsedNanos = if (observing) {
                    (System.nanoTime() - delegateStartedAtNanos).coerceAtLeast(0L)
                } else {
                    0L
                }
                log.warn(ex) { "leader.lease.auto-extend.suspend.failed" }
                BackendError(ex)
            } finally {
                watchdogReservation?.close()
            }

            publishLeaderLeaseWatchdogEvent(
                observing,
                LeaderLeaseExtensionExecution.SUSPEND,
                outcome,
                delegateElapsedNanos,
            )
            handleOutcome(outcome, errorClassifier, closed, futureRef, forceStop = delegateRejected)
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
                waitForInFlightExtend(extendInFlight)
                cancelScopeIfIdle()
            }
        }
    }

    /**
     * `renewalPeriod` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param leaseTime leadership을 보유할 수 있는 lease TTL입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun renewalPeriod(leaseTime: Duration): Duration {
        val third = leaseTime / 3
        return if (third > MIN_RENEWAL_PERIOD) third else MIN_RENEWAL_PERIOD
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private val MIN_RENEWAL_PERIOD = 25.milliseconds
    private const val CLOSE_WAIT_TIMEOUT_MILLIS = 5_000L
    private const val CLOSE_WAIT_POLL_MILLIS = 5L

    private fun waitForInFlightExtend(extendInFlight: AtomicBoolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_TIMEOUT_MILLIS)
        while (extendInFlight.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(CLOSE_WAIT_POLL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (extendInFlight.get()) {
            log.warn { "leader.lease.auto-extend.close timed out while waiting for in-flight extend" }
        }
    }

    private fun handleOutcome(
        outcome: ExtendOutcome,
        errorClassifier: BackendErrorClassifier,
        closed: AtomicBoolean,
        futureRef: AtomicReference<ScheduledFuture<*>?>,
        forceStop: Boolean = false,
    ) {
        if (forceStop) {
            log.warn { "leader.lease.auto-extend.stopped reason=DELEGATE_REJECTED" }
            if (closed.compareAndSet(false, true)) {
                futureRef.get()?.cancel(false)
            }
            return
        }
        when (outcome) {
            is Extended -> { /* 성공적으로 연장했으므로 계속 진행합니다. */ }
            is ExtendOutcome.Rejected -> {
                // Bounded watchdog admission is a transient lane decision. Keep the
                // watchdog alive so the next scheduled tick can retry ownership work.
                log.warn { "leader.lease.auto-extend.retry reason=ADMISSION_REJECTED" }
            }
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

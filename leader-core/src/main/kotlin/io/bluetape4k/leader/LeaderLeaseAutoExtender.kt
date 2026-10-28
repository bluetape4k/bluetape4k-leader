package io.bluetape4k.leader

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.leader.ExtendOutcome.BackendError
import io.bluetape4k.leader.ExtendOutcome.Extended
import io.bluetape4k.leader.ExtendOutcome.NotHeld
import io.bluetape4k.leader.ExtendOutcome.WrongThread
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.CoreBackendErrorClassifier
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.utils.Runtimex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun publishLeaderLeaseWatchdogEvent(
    observing: Boolean,
    observationScope: LeaderLeaseExtensionObservationScope?,
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
        observationScope,
    )
}

/**
 * 획득한 lease를 주기적으로 연장하는 shared watchdog scheduler를 관리합니다.
 *
 */
@Suppress("TooManyFunctions")
object LeaderLeaseAutoExtender: KLogging() {

    private val threadSeq = AtomicInteger()

    /**
     * 기본 watchdog scheduler thread 수입니다. 사용 가능한 processor 수와 `2` 중 큰 값입니다.
     */
    internal val DEFAULT_WATCHDOG_THREADS: Int = Runtimex.availableProcessors.coerceAtLeast(2)

    @Volatile
    private var configuredThreadCount: Int = DEFAULT_WATCHDOG_THREADS

    @Volatile
    private var asyncExtendEnabled: Boolean = false

    @Volatile
    private var scheduler: ScheduledThreadPoolExecutor = newScheduler()

    /**
     * 현재 watchdog scheduler의 thread 수를 반환합니다.
     */
    fun watchdogThreadCount(): Int = configuredThreadCount

    /**
     * scheduler 크기와 이후 watchdog이 사용할 연장 실행 모드를 설정합니다.
     * @param watchdogThreads scheduler thread 수입니다. `1` 이상이어야 하며 실행 중인 scheduler에도 즉시 적용됩니다.
     * @param asyncExtend `true`이면 다음 `start` 호출에서 연장을 별도 비동기 경로로 수행합니다. 이미 시작한 watchdog에는 적용되지 않습니다.
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
     * scheduler를 종료하고 최대 5초간 기다립니다. 그 안에 종료되지 않으면 실행 중인 작업을 중단하도록 요청합니다.
     */
    fun shutdown() {
        val current = scheduler
        current.shutdown()
        if (!current.awaitTermination(5.seconds)) {
            current.shutdownNow()
        }
    }

    /**
     * 종료된 경우 새 scheduler를 만듭니다. 아직 실행 중이면 아무 작업도 하지 않습니다.
     */
    fun restart() {
        if (!scheduler.isShutdown) return
        scheduler = newScheduler()
    }

    /**
     * scheduler가 종료되었는지 반환합니다.
     */
    fun isShutdown(): Boolean = scheduler.isShutdown

    private fun newScheduler(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(configuredThreadCount) { runnable ->
            Thread(runnable, "bluetape4k-leader-lease-watchdog-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.apply { removeOnCancelPolicy = true }

    /**
     * enabled이면 blocking backend 연장을 예약하고, handle을 닫으면 예약을 취소하고 진행 중 연장을 기다립니다.
     * disabled이면 동작하지 않는 closeable을 반환합니다.
     * @param enabled watchdog을 예약할지 결정합니다.
     * @param leaseTime backend에 유지할 lease TTL입니다. renewal 간격은 이 값의 약 1/3이며 최소 25ms입니다.
     * @param delegate 현재 lock의 backend 연장 및 소유권 확인 구현입니다.
     * @param classifier backend 오류의 일시성·중단 여부를 판정합니다. 생략하면 core 기본 classifier를 사용합니다.
     * @return 예약된 watchdog을 취소하고 진행 중 연장을 정리하는 handle입니다.
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
        val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()

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

            val observing = LeaderLeaseExtensionObservers.hasObservers(observationScope)
            val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
            var delegateRejected = false
            var delegateElapsedNanos = 0L
            val watchdogReservation = admission?.invoke()
            if (admission != null && watchdogReservation == null) {
                publishLeaderLeaseWatchdogEvent(
                    observing,
                    observationScope,
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
                observationScope,
                LeaderLeaseExtensionExecution.BLOCKING,
                outcome,
                delegateElapsedNanos,
            )
            handleOutcome(outcome, errorClassifier, closed, futureRef, forceStop = delegateRejected)
        }

        // async mode에서는 각 tick을 virtual thread로 dispatch해 느린 backend가 shared scheduler를 막지 않게 합니다.
        // extendInFlight guard는 extend 호출이 겹치지 않도록 막습니다.
        val tickRunnable: Runnable =
            if (capturedAsyncExtend) {
                Runnable {
                    if (extendInFlight.compareAndSet(false, true)) {
                        Thread.ofVirtual()
                            .name("leader-lease-extend-${threadSeq.incrementAndGet()}")
                            .start {
                                try {
                                    doTick()
                                } finally {
                                    extendInFlight.set(false)
                                }
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
     * async completion callback에서 watchdog drain을 별도 virtual thread로 이동합니다.
     *
     * `close()`의 in-flight extension 대기는 blocking 계약으로 유지하되, async caller가 직접
     * blocking하지 않도록 완료 future를 반환합니다.
     * @param watchdog 종료할 watchdog handle입니다.
     * @return watchdog drain이 끝난 뒤 완료되는 future입니다.
     */
    fun closeAsync(watchdog: AutoCloseable): CompletableFuture<Unit> {
        if (watchdog === NoopCloseable) return completableFutureOf(Unit)

        return futureOf(cleanupExecutor) {
            watchdog.close()
        }
    }

    /**
     * coroutine cleanup 경계에서 watchdog drain을 suspend 방식으로 기다립니다.
     *
     * 실제 blocking drain은 owned cleanup virtual thread에서 수행되므로 caller dispatcher를
     * 점유하지 않습니다. 호출부는 취소 불가능한 cleanup context에서 사용해야 합니다.
     * @param watchdog 종료할 watchdog handle입니다.
     */
    suspend fun closeSuspend(watchdog: AutoCloseable) {
        closeAsync(watchdog).await()
    }

    /**
     * enabled이면 suspend backend 연장을 예약하고, handle을 닫으면 예약을 취소하고 진행 중 연장을 기다립니다.
     * disabled이면 동작하지 않는 closeable을 반환합니다.
     * @param enabled watchdog을 예약할지 결정합니다.
     * @param leaseTime backend에 유지할 lease TTL입니다. renewal 간격은 이 값의 약 1/3이며 최소 25ms입니다.
     * @param delegate suspend backend의 연장 및 소유권 확인 구현입니다.
     * @param classifier backend 오류의 일시성·중단 여부를 판정합니다. 생략하면 core 기본 classifier를 사용합니다.
     * @return 예약된 watchdog을 취소하고 진행 중 연장을 정리하는 handle입니다.
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
        val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()

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

            val observing = LeaderLeaseExtensionObservers.hasObservers(observationScope)
            val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
            var delegateRejected = false
            var delegateElapsedNanos = 0L
            val watchdogReservation = admission?.invoke()

            if (admission != null && watchdogReservation == null) {
                publishLeaderLeaseWatchdogEvent(
                    observing,
                    observationScope,
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
                log.warn(ex) {
                    "Suspend watchdog delegate rejected extension. delegateId=${delegate.hashCode()}"
                }
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
                observationScope,
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
                }.log("Tick")
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
            log.warn(ex) {
                "Suspend watchdog scheduling rejected — scheduler shut down. Returning no-op."
            }
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
     * lease TTL의 1/3을 계산하되 연장 주기를 25ms보다 짧게 설정하지 않습니다.
     * @param leaseTime 연장 대상 lease TTL입니다.
     * @return `leaseTime / 3`과 25ms 중 큰 기간입니다.
     */
    fun renewalPeriod(leaseTime: Duration): Duration {
        val third = leaseTime / 3
        return if (third > MIN_RENEWAL_PERIOD) third else MIN_RENEWAL_PERIOD
    }

    private object NoopCloseable: AutoCloseable {
        override fun close() = Unit
    }

    private val MIN_RENEWAL_PERIOD = 25.milliseconds
    private const val CLOSE_WAIT_TIMEOUT_MILLIS = 5_000L
    private const val CLOSE_WAIT_POLL_MILLIS = 5L
    private val cleanupExecutor: Executor = Executor { runnable ->
        Thread.ofVirtual()
            .name("bluetape4k-leader-lease-cleanup-${threadSeq.incrementAndGet()}")
            .start(runnable)
    }

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
            is Extended               -> {
                /* 성공적으로 연장했으므로 계속 진행합니다. */
                log.info { "연장이 성공해서 계속 진행합니다." }
            }
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
            is BackendError           -> {
                val kind = errorClassifier.classify(outcome.cause) ?: BackendErrorKind.NON_TRANSIENT

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

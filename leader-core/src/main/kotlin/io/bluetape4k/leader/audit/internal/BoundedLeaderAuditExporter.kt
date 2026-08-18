@file:Suppress(
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "ReturnCount",
    "SwallowedException",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnusedParameter",
)

package io.bluetape4k.leader.audit.internal

import io.bluetape4k.leader.audit.LeaderAuditDelivery
import io.bluetape4k.leader.audit.LeaderAuditDeliveryResult
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObservation
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.audit.MAX_ATTEMPT_TIMEOUT_NANOS
import io.bluetape4k.leader.audit.MAX_BACKOFF_NANOS
import io.bluetape4k.leader.audit.toAuditPositiveNanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * caller-owned executor/scheduler 위에서 bounded admission과 retry를 수행하는 core exporter입니다.
 *
 * 이 구현은 queue, in-flight, scheduled retry를 하나의 `admitted` permit으로 묶습니다.
 * `submit`은 capacity를 기다리지 않고 즉시 결과를 반환하며, 외부 실행기를 종료하지 않습니다.
 */
internal class BoundedLeaderAuditExporter(
    private val delivery: LeaderAuditDelivery,
    private val options: LeaderAuditExportOptions,
) : LeaderAuditExporter {

    private val attemptTimeoutNanos = options.attemptTimeout.toAuditPositiveNanos(
        "attemptTimeout",
        MAX_ATTEMPT_TIMEOUT_NANOS,
    )
    private val initialBackoffNanos = options.initialBackoff.toAuditPositiveNanos(
        "initialBackoff",
        MAX_BACKOFF_NANOS,
    )
    private val maxBackoffNanos = options.maxBackoff.toAuditPositiveNanos(
        "maxBackoff",
        MAX_BACKOFF_NANOS,
    )

    private val admissionLock = ReentrantLock()
    private val diagnosticsLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val diagnosticsClosed = AtomicBoolean(false)
    private val workerRunning = AtomicBoolean(false)
    private val worker = ConcurrentLinkedQueue<WorkItem>()
    private val active = ConcurrentHashMap.newKeySet<WorkItem>()
    private val diagnostics = ConcurrentLinkedQueue<ObservationWork>()
    private val observerSlots = LinkedHashSet<ObserverSlot>()
    private val diagnosticsWorker = AtomicReference<Thread?>(null)
    private val diagnosticsQueued = AtomicInteger()
    private val diagnosticsCapacity = min(options.queueCapacity, MAX_DIAGNOSTICS_CAPACITY)

    private val queued = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val scheduledRetries = AtomicInteger()
    private val admitted = AtomicInteger()
    private val accepted = AtomicLong()
    private val droppedQueueFull = AtomicLong()
    private val droppedClosed = AtomicLong()
    private val retries = AtomicLong()
    private val terminalFailures = AtomicLong()
    private val cancellations = AtomicLong()
    private val executorRejections = AtomicLong()
    private val schedulerRejections = AtomicLong()
    private val observerDrops = AtomicLong()
    private val observerRegistrationDrops = AtomicLong()
    private val diagnosticsFatalErrors = AtomicLong()
    private val schedulingLock = ReentrantLock()
    private val schedulingComplete = schedulingLock.newCondition()
    private var schedulingCalls: Int = 0

    override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult {
        if (!admissionLock.tryLock()) {
            droppedQueueFull.incrementAndGet()
            publish(LeaderAuditExportObservation.DROPPED_QUEUE_FULL)
            return LeaderAuditSubmitResult.DROPPED_QUEUE_FULL
        }

        val item = try {
            if (closed.get()) {
                droppedClosed.incrementAndGet()
                publish(LeaderAuditExportObservation.DROPPED_CLOSED)
                return LeaderAuditSubmitResult.DROPPED_CLOSED
            }
            if (!reservePermit()) {
                droppedQueueFull.incrementAndGet()
                publish(LeaderAuditExportObservation.DROPPED_QUEUE_FULL)
                return LeaderAuditSubmitResult.DROPPED_QUEUE_FULL
            }
            WorkItem(event).also {
                active.add(it)
                queued.incrementAndGet()
                worker.offer(it)
                accepted.incrementAndGet()
                publish(LeaderAuditExportObservation.ACCEPTED)
            }
        } finally {
            admissionLock.unlock()
        }

        tryStartWorker()
        return if (item.terminalized.get()) {
            // A concurrent close may have won immediately after admission. The admission itself
            // remains ACCEPTED; terminal cancellation is observable separately.
            LeaderAuditSubmitResult.ACCEPTED
        } else {
            LeaderAuditSubmitResult.ACCEPTED
        }
    }

    override fun observe(observer: LeaderAuditExportObserver): AutoCloseable {
        diagnosticsLock.lock()
        try {
            if (diagnosticsClosed.get() || observerSlots.size >= MAX_OBSERVERS) {
                observerRegistrationDrops.incrementAndGet()
                return NoopCloseable
            }
            val slot = ObserverSlot(observer)
            observerSlots += slot
            return AutoCloseable { closeObserver(slot) }
        } finally {
            diagnosticsLock.unlock()
        }
    }

    override fun snapshot(): LeaderAuditExportSnapshot = LeaderAuditExportSnapshot.create(
        queued = queued.get(),
        inFlight = inFlight.get(),
        scheduledRetries = scheduledRetries.get(),
        admitted = admitted.get(),
        accepted = accepted.get(),
        droppedQueueFull = droppedQueueFull.get(),
        droppedClosed = droppedClosed.get(),
        retries = retries.get(),
        terminalFailures = terminalFailures.get(),
        cancellations = cancellations.get(),
        executorRejections = executorRejections.get(),
        schedulerRejections = schedulerRejections.get(),
        observerDrops = observerDrops.get(),
        observerRegistrationDrops = observerRegistrationDrops.get(),
        diagnosticsFatalErrors = diagnosticsFatalErrors.get(),
        diagnosticsClosed = diagnosticsClosed.get(),
        closed = closed.get(),
    )

    override fun close() {
        if (!admissionLock.tryLock()) {
            // close is allowed to wait for the short admission critical section; submit never
            // waits, preserving the hot-path non-blocking contract.
            admissionLock.lock()
        }
        try {
            if (closed.getAndSet(true)) return
            drainQueued(LeaderAuditExportObservation.CANCELLED)
        } finally {
            admissionLock.unlock()
        }

        awaitSchedulingQuiescence()
        // Serialize cancellation with the short attempt-start gate. This prevents a
        // timeout/close crossing from admitting a delivery after the exporter has
        // already published CLOSED.
        admissionLock.lock()
        try {
            active.toList().forEach(::cancelWork)
        } finally {
            admissionLock.unlock()
        }

        diagnosticsLock.lock()
        try {
            diagnosticsClosed.set(true)
            drainDiagnostics()
        } finally {
            diagnosticsLock.unlock()
        }
        diagnosticsWorker.get()?.let(LockSupport::unpark)
        tryStartWorker()
    }

    private fun reservePermit(): Boolean {
        while (true) {
            val current = admitted.get()
            if (current >= options.queueCapacity) return false
            if (admitted.compareAndSet(current, current + 1)) return true
        }
    }

    private fun tryStartWorker() {
        if (closed.get()) return
        if (!workerRunning.compareAndSet(false, true)) return
        // An Executor is allowed to run inline. Invoke it from a dedicated virtual
        // thread so submit() remains an admission-only, non-blocking boundary even
        // with DirectExecutor/CallerRunsPolicy implementations.
        Thread.ofVirtual()
            .name("bluetape4k-leader-audit-worker-dispatch")
            .start {
                try {
                    options.executor.execute(::runWorker)
                } catch (e: RejectedExecutionException) {
                    workerRunning.set(false)
                    terminalizeQueued(LeaderAuditExportObservation.EXECUTOR_REJECTED)
                } catch (e: Error) {
                    workerRunning.set(false)
                    terminalizeQueued(LeaderAuditExportObservation.EXECUTOR_REJECTED)
                    throw e
                }
            }
    }

    private fun runWorker() {
        var waitingForInFlight = false
        try {
            while (true) {
                val item = worker.poll() ?: break
                queued.decrementAndGet()
                if (item.terminalized.get()) continue
                if (closed.get()) {
                    finishWork(item, LeaderAuditExportObservation.CANCELLED)
                    continue
                }
                if (!reserveInFlight()) {
                    worker.offer(item)
                    queued.incrementAndGet()
                    waitingForInFlight = true
                    break
                }
                startAttempt(item)
            }
        } finally {
            workerRunning.set(false)
            if (shouldRestartWorker(waitingForInFlight)) {
                tryStartWorker()
            }
        }
    }

    private fun reserveInFlight(): Boolean {
        while (true) {
            val current = inFlight.get()
            if (current >= options.maxInFlight) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    private fun startAttempt(item: WorkItem) {
        if (closed.get()) {
            releaseInFlight()
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
            return
        }

        val attempt = Attempt(item.attempts + 1)
        item.attempts = attempt.number
        item.currentAttempt = attempt
        if (!beginScheduling()) {
            releaseAttempt(item, attempt)
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
            return
        }
        try {
            attempt.timeout = options.scheduler.schedule(
                { timeout(item, attempt) },
                attemptTimeoutNanos,
                TimeUnit.NANOSECONDS,
            )
        } catch (e: RejectedExecutionException) {
            releaseAttempt(item, attempt)
            finishWork(item, LeaderAuditExportObservation.SCHEDULER_REJECTED)
            return
        } finally {
            endScheduling()
        }

        // Close and timeout may win while the attempt timeout is being installed.
        // Re-check under the admission gate before invoking user delivery code.
        if (!prepareAttemptDelivery(item, attempt)) return

        val future = try {
            delivery.deliver(item.event)
        } catch (e: Throwable) {
            attempt.done.set(true)
            attempt.timeout?.cancel(false)
            releaseAttempt(item, attempt)
            if (e is Error) {
                finishWork(item, LeaderAuditExportObservation.TERMINAL_FAILURE)
                throw e
            }
            completeFailure(item, attempt, e)
            return
        }
        attempt.future = future
        if (attempt.done.get() || closed.get()) {
            if (attempt.done.compareAndSet(false, true)) {
                attempt.timeout?.cancel(false)
                future.cancel(true)
                releaseAttempt(item, attempt)
                finishWork(item, LeaderAuditExportObservation.CANCELLED)
            }
            future.cancel(true)
            return
        }
        future.whenComplete { result, failure ->
            complete(item, attempt, result, failure)
        }
    }

    private fun complete(
        item: WorkItem,
        attempt: Attempt,
        result: LeaderAuditDeliveryResult?,
        failure: Throwable?,
    ) {
        if (!attempt.done.compareAndSet(false, true)) return
        attempt.timeout?.cancel(false)
        if (failure is java.util.concurrent.CancellationException) {
            releaseAttempt(item, attempt)
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
            return
        }
        releaseAttempt(item, attempt)
        if (closed.get()) {
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
            return
        }
        when {
            failure is Error -> {
                finishWork(item, LeaderAuditExportObservation.TERMINAL_FAILURE)
                rethrowOnUncaughtBoundary(failure)
            }
            failure != null -> completeFailure(item, attempt, failure)
            result == LeaderAuditDeliveryResult.SUCCESS -> finishWork(item, null)
            result == LeaderAuditDeliveryResult.RETRYABLE_FAILURE -> retryOrFail(item)
            else -> finishWork(item, LeaderAuditExportObservation.TERMINAL_FAILURE)
        }
    }

    private fun prepareAttemptDelivery(item: WorkItem, attempt: Attempt): Boolean {
        admissionLock.lock()
        return try {
            if (!closed.get() && !attempt.done.get()) {
                attempt.deliveryStarted.set(true)
                true
            } else {
                if (attempt.done.compareAndSet(false, true)) {
                    attempt.timeout?.cancel(false)
                    releaseAttempt(item, attempt)
                    finishWork(item, LeaderAuditExportObservation.CANCELLED)
                }
                false
            }
        } finally {
            admissionLock.unlock()
        }
    }

    private fun completeFailure(item: WorkItem, attempt: Attempt, failure: Throwable) {
        if (failure is java.util.concurrent.CancellationException) {
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
        } else {
            retryOrFail(item)
        }
    }

    private fun timeout(item: WorkItem, attempt: Attempt) {
        if (!attempt.done.compareAndSet(false, true)) return
        attempt.future?.cancel(true)
        releaseAttempt(item, attempt)
        if (closed.get()) {
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
        } else {
            retryOrFail(item)
        }
    }

    private fun retryOrFail(item: WorkItem) {
        if (closed.get()) {
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
        } else if (item.attempts >= options.maxAttempts) {
            finishWork(item, LeaderAuditExportObservation.TERMINAL_FAILURE)
        } else {
            retries.incrementAndGet()
            publish(LeaderAuditExportObservation.RETRY)
            scheduleRetry(item)
        }
    }

    private fun scheduleRetry(item: WorkItem) {
        if (closed.get()) {
            finishWork(item, LeaderAuditExportObservation.CANCELLED)
            return
        }
        item.retryClaimed.set(false)
        scheduledRetries.incrementAndGet()
        val delay = saturatingBackoff(initialBackoffNanos, item.attempts)
        if (!beginScheduling()) {
            if (item.retryClaimed.compareAndSet(false, true)) {
                scheduledRetries.decrementAndGet()
                finishWork(item, LeaderAuditExportObservation.CANCELLED)
            }
            return
        }
        try {
            item.retry = options.scheduler.schedule(
                {
                    if (!item.retryClaimed.compareAndSet(false, true)) return@schedule
                    scheduledRetries.decrementAndGet()
                    var restart = false
                    admissionLock.lock()
                    try {
                        item.retry = null
                        if (closed.get() || item.terminalized.get()) {
                            finishWork(item, LeaderAuditExportObservation.CANCELLED)
                        } else {
                            worker.offer(item)
                            queued.incrementAndGet()
                            restart = true
                        }
                    } finally {
                        admissionLock.unlock()
                    }
                    if (restart) tryStartWorker()
                },
                delay,
                TimeUnit.NANOSECONDS,
            )
        } catch (e: RejectedExecutionException) {
            if (item.retryClaimed.compareAndSet(false, true)) {
                scheduledRetries.decrementAndGet()
                finishWork(item, LeaderAuditExportObservation.SCHEDULER_REJECTED)
            }
        } finally {
            endScheduling()
        }
    }

    private fun saturatingBackoff(initial: Long, attempt: Int): Long {
        var value = initial
        repeat((attempt - 1).coerceAtMost(62)) {
            if (value >= maxBackoffNanos / 2) return maxBackoffNanos
            value *= 2
        }
        return min(value, maxBackoffNanos)
    }

    private fun rethrowOnUncaughtBoundary(error: Error) {
        Thread.ofVirtual()
            .name("bluetape4k-leader-audit-fatal")
            .start { throw error }
    }

    private fun cancelWork(item: WorkItem) {
        val retry = item.retry
        if (retry != null && item.retryClaimed.compareAndSet(false, true)) {
            retry.cancel(false)
            scheduledRetries.decrementAndGet()
            item.retry = null
        }
        val attempt = item.currentAttempt
        if (attempt != null) {
            val future = attempt.future
            if (!attempt.deliveryStarted.get() || future != null) {
                if (attempt.done.compareAndSet(false, true)) {
                    attempt.timeout?.cancel(false)
                    future?.cancel(true)
                    releaseAttempt(item, attempt)
                }
            }
        }
        finishWork(item, LeaderAuditExportObservation.CANCELLED)
    }

    private fun releaseAttempt(item: WorkItem, attempt: Attempt) {
        if (item.currentAttempt === attempt) item.currentAttempt = null
        releaseInFlight()
    }

    private fun releaseInFlight() {
        inFlight.decrementAndGet()
        tryStartWorker()
    }

    private fun finishWork(item: WorkItem, observation: LeaderAuditExportObservation?) {
        if (!item.terminalized.compareAndSet(false, true)) return
        active.remove(item)
        admitted.decrementAndGet()
        if (observation != null) {
            when (observation) {
                LeaderAuditExportObservation.TERMINAL_FAILURE -> terminalFailures.incrementAndGet()
                LeaderAuditExportObservation.CANCELLED -> cancellations.incrementAndGet()
                LeaderAuditExportObservation.EXECUTOR_REJECTED -> executorRejections.incrementAndGet()
                LeaderAuditExportObservation.SCHEDULER_REJECTED -> schedulerRejections.incrementAndGet()
                else -> Unit
            }
            publish(observation)
        }
    }

    private fun drainQueued(observation: LeaderAuditExportObservation) {
        while (true) {
            val item = worker.poll() ?: break
            queued.decrementAndGet()
            finishWork(item, observation)
        }
    }

    private fun terminalizeQueued(observation: LeaderAuditExportObservation) {
        drainQueued(observation)
    }

    private fun publish(observation: LeaderAuditExportObservation) {
        if (diagnosticsClosed.get()) return
        if (!diagnosticsLock.tryLock()) {
            observerDrops.incrementAndGet()
            return
        }
        try {
            if (diagnosticsClosed.get()) return
            observerSlots.toList().forEach { slot ->
                if (!slot.active.get()) return@forEach
                while (true) {
                    val current = diagnosticsQueued.get()
                    if (current >= diagnosticsCapacity) {
                        observerDrops.incrementAndGet()
                        break
                    }
                    if (diagnosticsQueued.compareAndSet(current, current + 1)) {
                        diagnostics.offer(ObservationWork(slot, observation))
                        ensureDiagnosticsWorker()
                        break
                    }
                }
            }
        } finally {
            diagnosticsLock.unlock()
        }
    }

    private fun ensureDiagnosticsWorker() {
        val existing = diagnosticsWorker.get()
        if (existing != null && existing.isAlive) {
            LockSupport.unpark(existing)
            return
        }
        val thread = Thread.ofVirtual()
            .name("bluetape4k-leader-audit-observer")
            .unstarted(::runDiagnostics)
        if (diagnosticsWorker.compareAndSet(existing, thread)) thread.start() else thread.interrupt()
    }

    private fun runDiagnostics() {
        while (!diagnosticsClosed.get() || diagnostics.isNotEmpty()) {
            val work = diagnostics.poll()
            if (work == null) {
                LockSupport.park()
                continue
            }
            diagnosticsQueued.decrementAndGet()
            val reserved = diagnosticsLock.withLockIfAvailable {
                if (!diagnosticsClosed.get() && work.slot.active.get()) {
                    work.slot.running.incrementAndGet()
                    true
                } else {
                    false
                }
            }
            if (!reserved) continue
            try {
                work.slot.observer.onObservation(work.observation)
            } catch (e: Exception) {
                // observer failures are isolated from admission and delivery.
                BoundedLeaderAuditExporterLogger.log.warn {
                    "Leader audit observer failed and was isolated"
                }
            } catch (e: Error) {
                diagnosticsFatalErrors.incrementAndGet()
                diagnosticsLock.lock()
                try {
                    diagnosticsClosed.set(true)
                    drainDiagnostics()
                } finally {
                    diagnosticsLock.unlock()
                }
                throw e
            } finally {
                work.slot.running.decrementAndGet()
            }
        }
    }

    private fun closeObserver(slot: ObserverSlot) {
        diagnosticsLock.lock()
        try {
            if (!slot.active.getAndSet(false)) return
            observerSlots.remove(slot)
            diagnostics.removeIf { work ->
                if (work.slot !== slot) return@removeIf false
                diagnosticsQueued.decrementAndGet()
                true
            }
        } finally {
            diagnosticsLock.unlock()
        }
    }

    private fun drainDiagnostics() {
        while (diagnostics.poll() != null) diagnosticsQueued.decrementAndGet()
    }

    private class WorkItem(val event: LeaderAuditExportEvent) {
        var attempts: Int = 0
        @Volatile var currentAttempt: Attempt? = null
        @Volatile var retry: ScheduledFuture<*>? = null
        val retryClaimed = AtomicBoolean(true)
        val terminalized = AtomicBoolean(false)
    }

    private class Attempt(val number: Int) {
        val done = AtomicBoolean(false)
        val deliveryStarted = AtomicBoolean(false)
        @Volatile var future: CompletableFuture<LeaderAuditDeliveryResult>? = null
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    private class ObserverSlot(val observer: LeaderAuditExportObserver) {
        val active = AtomicBoolean(true)
        val running = AtomicInteger()
    }

    private data class ObservationWork(
        val slot: ObserverSlot,
        val observation: LeaderAuditExportObservation,
    )

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private companion object {
        const val MAX_DIAGNOSTICS_CAPACITY: Int = 1024
        const val MAX_OBSERVERS: Int = 16
    }

    private fun beginScheduling(): Boolean {
        schedulingLock.lock()
        return try {
            if (closed.get()) {
                false
            } else {
                schedulingCalls++
                true
            }
        } finally {
            schedulingLock.unlock()
        }
    }

    private fun endScheduling() {
        schedulingLock.lock()
        try {
            schedulingCalls--
            if (schedulingCalls == 0) schedulingComplete.signalAll()
        } finally {
            schedulingLock.unlock()
        }
    }

    private fun awaitSchedulingQuiescence() {
        schedulingLock.lock()
        try {
            while (schedulingCalls > 0) schedulingComplete.awaitUninterruptibly()
        } finally {
            schedulingLock.unlock()
        }
    }

    private fun shouldRestartWorker(waitingForInFlight: Boolean): Boolean {
        if (worker.isEmpty() || closed.get()) return false
        return !waitingForInFlight || inFlight.get() < options.maxInFlight
    }
}

private object BoundedLeaderAuditExporterLogger : KLogging()

private inline fun <T> ReentrantLock.withLockIfAvailable(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

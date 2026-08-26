package io.bluetape4k.leader

import io.bluetape4k.leader.internal.LeaderManagementActionStore
import io.bluetape4k.leader.internal.LeaseOperationScheduler
import io.bluetape4k.leader.internal.MonotonicDeadline
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 명시적으로 등록한 blocking lease를 운영 action으로 해제하는 process-local registry입니다.
 *
 * registry는 backend handle의 소유권이나 애플리케이션 작업을 대신 관리하지 않습니다.
 * 등록 token과 bounded action worker만 소유하며, release callback은 registry mutex 밖에서
 * 한 번만 실행합니다.
 */
class LeaderManagementActionRegistry(
    private val observer: LeaderManagementActionObserver? = null,
    private val actionTimeout: Duration = 5.seconds,
    private val cleanupGrace: Duration = 30.seconds,
    maxInFlightActions: Int = 16,
    actionQueueCapacity: Int = 32,
    maxRegistrations: Int = 1_024,
    private val closeTimeout: Duration = 5.seconds,
) : AutoCloseable {

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 30L
        const val MAX_IN_FLIGHT_ACTIONS = 256
        const val MAX_ACTION_QUEUE_CAPACITY = 1_024
        const val MAX_REGISTRATIONS = 65_536
        const val WATCHER_POLL_MILLIS = 1L
    }

    private val scheduler: LeaseOperationScheduler
    private val store: LeaderManagementActionStore

    init {
        actionTimeout.requireBoundedPositive("actionTimeout")
        cleanupGrace.requireBoundedPositive("cleanupGrace")
        closeTimeout.requireBoundedPositive("closeTimeout")
        maxInFlightActions.requirePositiveNumber("maxInFlightActions")
        maxInFlightActions.requireLe(MAX_IN_FLIGHT_ACTIONS, "maxInFlightActions")
        actionQueueCapacity.requirePositiveNumber("actionQueueCapacity")
        actionQueueCapacity.requireLe(MAX_ACTION_QUEUE_CAPACITY, "actionQueueCapacity")
        maxRegistrations.requirePositiveNumber("maxRegistrations")
        maxRegistrations.requireLe(MAX_REGISTRATIONS, "maxRegistrations")

        scheduler = LeaseOperationScheduler(
            maxInFlight = maxInFlightActions,
            queueCapacity = actionQueueCapacity,
            threadNamePrefix = "bluetape4k-leader-management",
        )
        store = LeaderManagementActionStore(
            maxRegistrations = maxRegistrations,
            maxActionReservations = maxInFlightActions + actionQueueCapacity,
        )
    }

    /** 등록된 handle을 identity 기준으로 참조 계수합니다. backend callback은 호출하지 않습니다. */
    fun register(handle: LeaderLeaseHandle): LeaderManagementRegistration {
        val lockName = handle.lockName
        if (!isManagementActionLockName(lockName)) {
            return LeaderManagementRegistration(
                accepted = false,
                outcome = LeaderManagementRegistrationOutcome.INVALID_LOCK_NAME,
            )
        }

        return when (val decision = store.register(handle)) {
            is LeaderManagementActionStore.RegistrationDecision.Accepted ->
                LeaderManagementRegistration(
                    accepted = true,
                    outcome = LeaderManagementRegistrationOutcome.ACCEPTED,
                    onClose = { store.closeRegistration(decision.record) },
                )

            is LeaderManagementActionStore.RegistrationDecision.Rejected ->
                LeaderManagementRegistration(
                    accepted = false,
                    outcome = decision.outcome,
                )
        }
    }

    /**
     * lock 이름을 정확히 하나의 등록 handle에 선형화한 뒤 bounded worker로 release합니다.
     * 정상 contention과 backend 예외는 sanitized result로 반환하며, [Error]만 재전파합니다.
     */
    fun release(lockName: String): LeaderManagementActionResult {
        val deadline = MonotonicDeadline.fromNow(actionTimeout)
        if (!isManagementActionLockName(lockName)) {
            return immediate(LeaderManagementActionOutcome.INVALID_LOCK_NAME)
        }

        when (val selection = store.select(lockName)) {
            LeaderManagementActionStore.Selection.Closed ->
                return immediate(LeaderManagementActionOutcome.REGISTRY_CLOSED)

            LeaderManagementActionStore.Selection.NotRegistered ->
                return immediate(LeaderManagementActionOutcome.NOT_REGISTERED)

            LeaderManagementActionStore.Selection.Ambiguous ->
                return immediate(LeaderManagementActionOutcome.AMBIGUOUS)

            is LeaderManagementActionStore.Selection.Record -> {
                val (beginOutcome, action) = store.beginRecord(selection.value)
                when (beginOutcome) {
                    LeaderManagementActionStore.BeginOutcome.REGISTRY_CLOSED ->
                        return immediate(LeaderManagementActionOutcome.REGISTRY_CLOSED)

                    LeaderManagementActionStore.BeginOutcome.NOT_REGISTERED ->
                        return immediate(LeaderManagementActionOutcome.NOT_REGISTERED)

                    LeaderManagementActionStore.BeginOutcome.AMBIGUOUS ->
                        return immediate(LeaderManagementActionOutcome.AMBIGUOUS)

                    LeaderManagementActionStore.BeginOutcome.ACTION_IN_PROGRESS ->
                        return immediate(LeaderManagementActionOutcome.ACTION_IN_PROGRESS)

                    LeaderManagementActionStore.BeginOutcome.ACTION_ADMISSION_REJECTED ->
                        return immediate(LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED)

                    LeaderManagementActionStore.BeginOutcome.STARTED -> Unit
                }

                val actionRecord = checkNotNull(action)
                val future = scheduler.submit { runAction(actionRecord, deadline) }
                if (future == null) {
                    store.finish(actionRecord)
                    return immediate(LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED)
                }
                actionRecord.future = future
                return awaitResult(actionRecord, future, deadline)
            }
        }
    }

    /** action-addressable 등록 이름을 정렬된 snapshot으로 반환합니다. */
    fun registeredLockNames(): List<String> = store.registeredLockNames()

    /** worker가 실제로 종료되지 않은 quarantine reservation 수입니다. */
    fun quarantinedCount(): Int = store.quarantinedCount()

    /** 신규 admission을 막고 기존 worker를 bounded하게 drain합니다. */
    fun closeAndDrain(): Boolean {
        if (!store.beginQuiescing()) return true
        val deadline = MonotonicDeadline.fromNow(closeTimeout)
        var interrupted = false
        while (store.activeActionCount() > 0 && deadline.hasTimeRemaining()) {
            try {
                Thread.sleep(WATCHER_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
                break
            }
        }

        val drained = store.activeActionCount() == 0
        if (!drained) {
            store.activeActionRecords().forEach { action ->
                action.timedOut.set(true)
                val mutationAttempted = action.phase.get() >= LeaderManagementActionPhase.RELEASE_STARTED
                quarantineAndTerminalize(
                    action = action,
                    result = LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.ACTION_TIMED_OUT,
                        mutationAttempted,
                    ),
                    reason = LeaderManagementQuarantineReason.CLOSE_TIMEOUT,
                )
                cancelBeforeWorkerStarts(action)
            }
        }

        val schedulerClosed = scheduler.close(deadline.remainingDuration())
        store.closeLifecycle()
        if (interrupted) return false
        return drained && schedulerClosed
    }

    override fun close() {
        closeAndDrain()
    }

    private fun runAction(
        action: LeaderManagementActionStore.ActionRecord,
        deadline: MonotonicDeadline,
    ): LeaderManagementActionResult {
        action.workerStarted.set(true)
        try {
            if (action.timedOut.get() || !action.phase.compareAndSet(
                    LeaderManagementActionPhase.ADMITTED,
                    LeaderManagementActionPhase.PRECHECK,
                )
            ) {
                return terminalResult(action, timeoutResult(action))
            }
            if (action.timedOut.get() || !deadline.hasTimeRemaining()) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }

            val ownership = try {
                action.registration.handle.ownershipStatus()
            } catch (_: RuntimeException) {
                return terminalResult(
                    action,
                    LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN,
                        mutationAttempted = false,
                    ),
                )
            } catch (error: Error) {
                terminalize(
                    action,
                    LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN,
                        mutationAttempted = false,
                    ),
                )
                throw error
            }

            if (action.timedOut.get() || !deadline.hasTimeRemaining()) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }
            when (ownership) {
                LeaseOwnershipStatus.NOT_HELD ->
                    return terminalResult(
                        action,
                        LeaderManagementActionResult(
                            LeaderManagementAction.RELEASE,
                            LeaderManagementActionOutcome.NOT_HELD,
                            mutationAttempted = false,
                        ),
                    )

                LeaseOwnershipStatus.UNKNOWN ->
                    return terminalResult(
                        action,
                        LeaderManagementActionResult(
                            LeaderManagementAction.RELEASE,
                            LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN,
                            mutationAttempted = false,
                        ),
                    )

                LeaseOwnershipStatus.HELD -> Unit
            }

            if (action.timedOut.get() || !deadline.hasTimeRemaining() || !action.phase.compareAndSet(
                    LeaderManagementActionPhase.PRECHECK,
                    LeaderManagementActionPhase.RELEASE_STARTED,
                )
            ) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }
            action.mutationAttempted.set(true)

            try {
                action.registration.handle.release()
            } catch (_: RuntimeException) {
                return terminalResult(
                    action,
                    if (action.timedOut.get()) timeoutResult(action)
                    else LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.RELEASE_FAILED,
                        mutationAttempted = true,
                    ),
                )
            } catch (error: Error) {
                val result = if (action.timedOut.get()) timeoutResult(action) else LeaderManagementActionResult(
                    LeaderManagementAction.RELEASE,
                    LeaderManagementActionOutcome.RELEASE_FAILED,
                    mutationAttempted = true,
                )
                quarantineAndTerminalize(action, result, LeaderManagementQuarantineReason.CALLBACK_ERROR)
                throw error
            }

            if (action.timedOut.get() || !deadline.hasTimeRemaining()) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }
            if (!action.phase.compareAndSet(
                    LeaderManagementActionPhase.RELEASE_STARTED,
                    LeaderManagementActionPhase.POSTCHECK,
                )
            ) {
                return terminalResult(action, timeoutResult(action))
            }

            val postCheck = try {
                action.registration.handle.ownershipStatus()
            } catch (_: RuntimeException) {
                return terminalResult(
                    action,
                    if (action.timedOut.get()) timeoutResult(action) else LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
                        mutationAttempted = true,
                    ),
                )
            } catch (error: Error) {
                val result = if (action.timedOut.get()) timeoutResult(action) else LeaderManagementActionResult(
                    LeaderManagementAction.RELEASE,
                    LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
                    mutationAttempted = true,
                )
                quarantineAndTerminalize(action, result, LeaderManagementQuarantineReason.CALLBACK_ERROR)
                throw error
            }

            return terminalResult(
                action,
                when {
                    action.timedOut.get() -> timeoutResult(action)
                    postCheck == LeaseOwnershipStatus.NOT_HELD -> LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.RELEASED,
                        mutationAttempted = true,
                    )

                    else -> LeaderManagementActionResult(
                        LeaderManagementAction.RELEASE,
                        LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
                        mutationAttempted = true,
                    )
                },
            )
        } finally {
            action.workerFinished.countDown()
            store.finish(action)
        }
    }

    private fun awaitResult(
        action: LeaderManagementActionStore.ActionRecord,
        future: Future<LeaderManagementActionResult>,
        deadline: MonotonicDeadline,
    ): LeaderManagementActionResult {
        return try {
            val remaining = deadline.remainingNanos()
            if (remaining <= 0L) throw TimeoutException()
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            timeoutAction(action)
        } catch (_: CancellationException) {
            timeoutAction(action)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            timeoutAction(action)
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            if (cause is Error) throw cause
            action.result ?: timeoutAction(action)
        }
    }

    private fun timeoutAction(action: LeaderManagementActionStore.ActionRecord): LeaderManagementActionResult {
        action.timedOut.set(true)
        val result = timeoutResult(action)
        val future = action.future
        val cancelled = future?.cancel(true) == true
        if (cancelled && !action.workerStarted.get()) {
            terminalize(action, result)
            action.workerFinished.countDown()
            store.finish(action)
        } else {
            startCleanupWatcher(action, result)
        }
        return result
    }

    private fun startCleanupWatcher(
        action: LeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
    ) {
        if (!action.watcherStarted.compareAndSet(false, true)) return
        Thread({
            try {
                if (!action.workerFinished.await(cleanupGrace.inWholeNanoseconds, TimeUnit.NANOSECONDS)) {
                    quarantineAndTerminalize(
                        action,
                        result,
                        quarantineReason(action.phase.get()),
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                quarantineAndTerminalize(
                    action,
                    result,
                    quarantineReason(action.phase.get()),
                )
            }
        }, "bluetape4k-leader-management-watchdog").apply { isDaemon = true }.start()
    }

    private fun cancelBeforeWorkerStarts(action: LeaderManagementActionStore.ActionRecord) {
        val future = action.future ?: return
        val cancelled = future.cancel(true)
        if (cancelled && !action.workerStarted.get()) {
            action.workerFinished.countDown()
            store.finish(action)
        }
    }

    private fun terminalResult(
        action: LeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
    ): LeaderManagementActionResult {
        terminalize(action, result)
        return action.result ?: result
    }

    private fun terminalize(
        action: LeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
    ): Boolean {
        if (!action.terminal.compareAndSet(false, true)) return false
        action.result = result
        action.phase.set(LeaderManagementActionPhase.TERMINALIZED)
        notifyObserver(action, result, quarantined = false, quarantineReason = null)
        return true
    }

    private fun quarantineAndTerminalize(
        action: LeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
        reason: LeaderManagementQuarantineReason,
    ) {
        store.quarantine(action)
        if (!action.terminal.compareAndSet(false, true)) {
            action.phase.set(LeaderManagementActionPhase.QUARANTINED)
            action.quarantineReason = reason
            action.quarantined = true
            return
        }
        action.result = result
        action.phase.set(LeaderManagementActionPhase.QUARANTINED)
        action.quarantineReason = reason
        action.quarantined = true
        notifyObserver(action, result, quarantined = true, quarantineReason = reason)
    }

    private fun notifyObserver(
        action: LeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
        quarantined: Boolean,
        quarantineReason: LeaderManagementQuarantineReason?,
    ) {
        try {
            observer?.onResult(
                LeaderManagementActionObservation(
                    surface = LeaderManagementActionSurface.CORE,
                    outcome = result.outcome,
                    phase = action.phase.get(),
                    mutationAttempted = result.mutationAttempted,
                    quarantined = quarantined,
                    quarantineReason = quarantineReason,
                ),
            )
        } catch (_: Throwable) {
            // observer is application-owned and must not alter action result or cleanup.
        }
    }

    private fun immediate(outcome: LeaderManagementActionOutcome): LeaderManagementActionResult {
        val result = LeaderManagementActionResult(
            action = LeaderManagementAction.RELEASE,
            outcome = outcome,
            mutationAttempted = false,
        )
        observer?.let {
            try {
                it.onResult(
                    LeaderManagementActionObservation(
                        surface = LeaderManagementActionSurface.CORE,
                        outcome = outcome,
                        phase = LeaderManagementActionPhase.TERMINALIZED,
                        mutationAttempted = false,
                        quarantined = false,
                    ),
                )
            } catch (_: Throwable) {
                // observer failure is intentionally isolated.
            }
        }
        return result
    }

    private fun timeoutResult(action: LeaderManagementActionStore.ActionRecord): LeaderManagementActionResult =
        LeaderManagementActionResult(
            action = LeaderManagementAction.RELEASE,
            outcome = LeaderManagementActionOutcome.ACTION_TIMED_OUT,
            mutationAttempted = action.mutationAttempted.get() ||
                action.phase.get() in setOf(
                    LeaderManagementActionPhase.RELEASE_STARTED,
                    LeaderManagementActionPhase.POSTCHECK,
                ),
        )

    private fun quarantineReason(phase: LeaderManagementActionPhase): LeaderManagementQuarantineReason =
        when (phase) {
            LeaderManagementActionPhase.RELEASE_STARTED,
            LeaderManagementActionPhase.POSTCHECK,
            -> LeaderManagementQuarantineReason.NON_INTERRUPTIBLE

            else -> LeaderManagementQuarantineReason.CLEANUP_TIMEOUT
        }

    private fun Duration.requireBoundedPositive(name: String) {
        require(isFinite()) { "$name must be finite: $this" }
        requireGt(Duration.ZERO, name)
        requireLe(MAX_TIMEOUT_SECONDS.seconds, name)
    }

    private fun MonotonicDeadline.remainingDuration(): Duration =
        remainingNanos().coerceAtLeast(0L).nanoseconds
}

package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderManagementAction
import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionPhase
import io.bluetape4k.leader.LeaderManagementActionObserver
import io.bluetape4k.leader.LeaderManagementActionResult
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.LeaderManagementQuarantineReason
import io.bluetape4k.leader.LeaderManagementRegistration
import io.bluetape4k.leader.LeaderManagementRegistrationOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.isManagementActionLockName
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * suspend lease를 caller cancellation과 분리된 registry-owned worker로 해제합니다.
 *
 * blocking registry와 동일한 identity registration, ownership truth table, sanitized
 * observer를 사용하지만 blocking executor나 `runBlocking`은 사용하지 않습니다.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class SuspendLeaderManagementActionRegistry(
    private val observer: LeaderManagementActionObserver? = null,
    private val actionTimeout: Duration = 5.seconds,
    private val cleanupGrace: Duration = 30.seconds,
    maxInFlightActions: Int = 16,
    maxRegistrations: Int = 1_024,
    private val closeTimeout: Duration = 5.seconds,
) : AutoCloseable {

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 30L
        const val MAX_IN_FLIGHT_ACTIONS = 256
        const val MAX_REGISTRATIONS = 65_536
        const val POLL_MILLIS = 1L
    }

    private val store = SuspendLeaderManagementActionStore(
        maxRegistrations = maxRegistrations,
        maxInFlightActions = maxInFlightActions,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        actionTimeout.requireBoundedPositive("actionTimeout")
        cleanupGrace.requireBoundedPositive("cleanupGrace")
        closeTimeout.requireBoundedPositive("closeTimeout")
        maxInFlightActions.requirePositiveNumber("maxInFlightActions")
        maxInFlightActions.requireLe(MAX_IN_FLIGHT_ACTIONS, "maxInFlightActions")
        maxRegistrations.requirePositiveNumber("maxRegistrations")
        maxRegistrations.requireLe(MAX_REGISTRATIONS, "maxRegistrations")
    }

    /** 등록 token을 identity 기준으로 참조 계수합니다. */
    fun register(handle: SuspendLeaderLeaseHandle): LeaderManagementRegistration {
        val lockName = handle.lockName
        if (!isManagementActionLockName(lockName)) {
            return LeaderManagementRegistration(
                accepted = false,
                outcome = LeaderManagementRegistrationOutcome.INVALID_LOCK_NAME,
            )
        }
        return when (val decision = store.register(handle)) {
            is SuspendLeaderManagementActionStore.RegistrationDecision.Accepted ->
                LeaderManagementRegistration(
                    accepted = true,
                    outcome = LeaderManagementRegistrationOutcome.ACCEPTED,
                    onClose = { store.closeRegistration(decision.record) },
                )

            is SuspendLeaderManagementActionStore.RegistrationDecision.Rejected ->
                LeaderManagementRegistration(false, decision.outcome)
        }
    }

    /** caller deadline까지만 결과를 관찰하고, cleanup worker는 registry scope에 남깁니다. */
    @Suppress("ReturnCount")
    suspend fun release(lockName: String): LeaderManagementActionResult {
        if (!isManagementActionLockName(lockName)) {
            return immediate(LeaderManagementActionOutcome.INVALID_LOCK_NAME)
        }
        when (val selection = store.select(lockName)) {
            SuspendLeaderManagementActionStore.Selection.Closed ->
                return immediate(LeaderManagementActionOutcome.REGISTRY_CLOSED)

            SuspendLeaderManagementActionStore.Selection.NotRegistered ->
                return immediate(LeaderManagementActionOutcome.NOT_REGISTERED)

            SuspendLeaderManagementActionStore.Selection.Ambiguous ->
                return immediate(LeaderManagementActionOutcome.AMBIGUOUS)

            is SuspendLeaderManagementActionStore.Selection.Record -> {
                val (outcome, action) = store.begin(selection.value)
                when (outcome) {
                    SuspendLeaderManagementActionStore.BeginOutcome.REGISTRY_CLOSED ->
                        return immediate(LeaderManagementActionOutcome.REGISTRY_CLOSED)

                    SuspendLeaderManagementActionStore.BeginOutcome.NOT_REGISTERED ->
                        return immediate(LeaderManagementActionOutcome.NOT_REGISTERED)

                    SuspendLeaderManagementActionStore.BeginOutcome.AMBIGUOUS ->
                        return immediate(LeaderManagementActionOutcome.AMBIGUOUS)

                    SuspendLeaderManagementActionStore.BeginOutcome.ACTION_IN_PROGRESS ->
                        return immediate(LeaderManagementActionOutcome.ACTION_IN_PROGRESS)

                    SuspendLeaderManagementActionStore.BeginOutcome.ACTION_ADMISSION_REJECTED ->
                        return immediate(LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED)

                    SuspendLeaderManagementActionStore.BeginOutcome.STARTED -> Unit
                }
                val actionRecord = checkNotNull(action)
                val deferred = CompletableDeferred<LeaderManagementActionResult>()
                actionRecord.deferred = deferred
                val job = scope.launch {
                    try {
                        deferred.complete(runAction(actionRecord))
                    } catch (error: Throwable) {
                        deferred.completeExceptionally(error)
                    }
                }
                actionRecord.job = job
                return awaitResult(actionRecord, deferred)
            }
        }
    }

    fun registeredLockNames(): List<String> = store.registeredLockNames()

    fun quarantinedCount(): Int = store.quarantinedCount()

    /** 신규 admission을 막고 기존 worker가 끝날 때까지 bounded하게 기다립니다. */
    suspend fun closeAndDrain(): Boolean {
        if (!store.beginQuiescing()) return true
        val drained = withTimeoutOrNull(closeTimeout) {
            while (store.activeActionCount() > 0) delay(POLL_MILLIS)
            true
        } ?: false

        if (!drained) {
            store.activeActionRecords().forEach { action ->
                action.timedOut.set(true)
                quarantineAndTerminalize(
                    action,
                    timeoutResult(action),
                    LeaderManagementQuarantineReason.CLOSE_TIMEOUT,
                )
                cancelBeforeWorkerStarts(action)
            }
        }
        scope.cancel()
        store.closeLifecycle()
        return drained
    }

    /** non-blocking close; suspend caller는 [closeAndDrain]으로 bounded 결과를 관찰합니다. */
    override fun close() {
        store.beginQuiescing()
        store.activeActionRecords().forEach { action ->
            action.timedOut.set(true)
            quarantineAndTerminalize(
                action,
                timeoutResult(action),
                LeaderManagementQuarantineReason.CLOSE_TIMEOUT,
            )
            cancelBeforeWorkerStarts(action)
        }
        scope.cancel()
        store.closeLifecycle()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
    private suspend fun runAction(
        action: SuspendLeaderManagementActionStore.ActionRecord,
    ): LeaderManagementActionResult {
        action.workerStarted.set(true)
        try {
            if (action.timedOut.get() || action.cancelRequested.get() || !action.phase.compareAndSet(
                    LeaderManagementActionPhase.ADMITTED,
                    LeaderManagementActionPhase.PRECHECK,
                )
            ) return terminalResult(action, timeoutResult(action))

            val ownership = try {
                action.registration.handle.ownershipStatus()
            } catch (_: CancellationException) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            } catch (_: RuntimeException) {
                return terminalResult(action, result(LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN, false))
            } catch (error: Error) {
                terminalize(action, result(LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN, false))
                throw error
            }

            if (action.timedOut.get() || action.cancelRequested.get()) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }
            when (ownership) {
                LeaseOwnershipStatus.NOT_HELD ->
                    return terminalResult(action, result(LeaderManagementActionOutcome.NOT_HELD, false))

                LeaseOwnershipStatus.UNKNOWN ->
                    return terminalResult(action, result(LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN, false))

                LeaseOwnershipStatus.HELD -> Unit
            }

            if (action.timedOut.get() || action.cancelRequested.get() || !action.phase.compareAndSet(
                    LeaderManagementActionPhase.PRECHECK,
                    LeaderManagementActionPhase.RELEASE_STARTED,
                )
            ) {
                action.timedOut.set(true)
                return terminalResult(action, timeoutResult(action))
            }
            action.mutationAttempted.set(true)

            try {
                withContext(NonCancellable) { action.registration.handle.release() }
            } catch (_: RuntimeException) {
                return terminalResult(
                    action,
                    if (action.timedOut.get()) timeoutResult(action)
                    else result(LeaderManagementActionOutcome.RELEASE_FAILED, true),
                )
            } catch (error: Error) {
                val terminal = if (action.timedOut.get()) timeoutResult(action)
                else result(LeaderManagementActionOutcome.RELEASE_FAILED, true)
                quarantineAndTerminalize(action, terminal, LeaderManagementQuarantineReason.CALLBACK_ERROR)
                throw error
            }

            if (action.timedOut.get()) return terminalResult(action, timeoutResult(action))
            if (!action.phase.compareAndSet(
                    LeaderManagementActionPhase.RELEASE_STARTED,
                    LeaderManagementActionPhase.POSTCHECK,
                )
            ) return terminalResult(action, timeoutResult(action))

            val postCheck = try {
                withContext(NonCancellable) { action.registration.handle.ownershipStatus() }
            } catch (_: RuntimeException) {
                return terminalResult(
                    action,
                    if (action.timedOut.get()) timeoutResult(action)
                    else result(LeaderManagementActionOutcome.RELEASE_UNCONFIRMED, true),
                )
            } catch (error: Error) {
                val terminal = if (action.timedOut.get()) timeoutResult(action)
                else result(LeaderManagementActionOutcome.RELEASE_UNCONFIRMED, true)
                quarantineAndTerminalize(action, terminal, LeaderManagementQuarantineReason.CALLBACK_ERROR)
                throw error
            }
            return terminalResult(
                action,
                when {
                    action.timedOut.get() -> timeoutResult(action)
                    postCheck == LeaseOwnershipStatus.NOT_HELD -> result(LeaderManagementActionOutcome.RELEASED, true)
                    else -> result(LeaderManagementActionOutcome.RELEASE_UNCONFIRMED, true)
                },
            )
        } finally {
            notifyQuarantineRecovery(action)
            action.workerFinished.complete(Unit)
            store.finish(action)
        }
    }

    private suspend fun awaitResult(
        action: SuspendLeaderManagementActionStore.ActionRecord,
        deferred: CompletableDeferred<LeaderManagementActionResult>,
    ): LeaderManagementActionResult {
        return try {
            withTimeoutOrNull(actionTimeout) { deferred.await() } ?: timeoutAction(action)
        } catch (cancelled: CancellationException) {
            action.cancelRequested.set(true)
            if (!action.mutationAttempted.get()) {
                action.job?.cancel(cancelled)
                if (action.workerStarted.get()) startCleanupWatcher(action) else cancelBeforeWorkerStarts(action)
            } else {
                startCleanupWatcher(action)
            }
            throw cancelled
        }
    }

    private fun timeoutAction(action: SuspendLeaderManagementActionStore.ActionRecord): LeaderManagementActionResult {
        action.timedOut.set(true)
        val result = timeoutResult(action)
        if (!action.mutationAttempted.get()) {
            action.job?.cancel()
            if (action.workerStarted.get()) startCleanupWatcher(action) else cancelBeforeWorkerStarts(action)
        } else {
            startCleanupWatcher(action)
        }
        return result
    }

    private fun startCleanupWatcher(action: SuspendLeaderManagementActionStore.ActionRecord) {
        if (!action.watcherStarted.compareAndSet(false, true)) return
        scope.launch {
            if (!action.workerFinished.awaitWithTimeout(cleanupGrace)) {
                quarantineAndTerminalize(
                    action,
                    timeoutResult(action),
                    quarantineReason(action.phase.get()),
                )
                action.job?.cancel()
            }
        }
    }

    private fun cancelBeforeWorkerStarts(action: SuspendLeaderManagementActionStore.ActionRecord) {
        if (!action.workerStarted.get()) {
            terminalize(action, timeoutResult(action))
            action.workerFinished.complete(Unit)
            store.finish(action)
        }
    }

    private fun terminalResult(
        action: SuspendLeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
    ): LeaderManagementActionResult {
        terminalize(action, result)
        return action.result ?: result
    }

    private fun terminalize(
        action: SuspendLeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
    ): Boolean {
        if (!action.terminal.compareAndSet(false, true)) return false
        action.result = result
        action.phase.set(LeaderManagementActionPhase.TERMINALIZED)
        notifyObserver(action, result, false, null)
        return true
    }

    private fun quarantineAndTerminalize(
        action: SuspendLeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
        reason: LeaderManagementQuarantineReason,
    ) {
        store.quarantine(action)
        if (!action.terminal.compareAndSet(false, true)) {
            action.phase.set(LeaderManagementActionPhase.QUARANTINED)
            action.quarantined = true
            action.quarantineReason = reason
            return
        }
        action.result = result
        action.phase.set(LeaderManagementActionPhase.QUARANTINED)
        action.quarantined = true
        action.quarantineReason = reason
        notifyObserver(action, result, true, reason)
    }

    private fun notifyObserver(
        action: SuspendLeaderManagementActionStore.ActionRecord,
        result: LeaderManagementActionResult,
        quarantined: Boolean,
        reason: LeaderManagementQuarantineReason?,
    ) {
        try {
            observer?.onResult(
                LeaderManagementActionObservation(
                    surface = LeaderManagementActionSurface.CORE,
                    outcome = result.outcome,
                    phase = action.phase.get(),
                    mutationAttempted = result.mutationAttempted,
                    quarantined = quarantined,
                    quarantineReason = reason,
                ),
            )
        } catch (_: Throwable) {
            // observer failure never changes result or cleanup.
        }
    }

    private fun notifyQuarantineRecovery(action: SuspendLeaderManagementActionStore.ActionRecord) {
        if (!action.quarantined) return
        val result = action.result
        val reason = action.quarantineReason
        if (result == null || reason == null) return
        try {
            observer?.onQuarantineRecovered(
                LeaderManagementActionObservation(
                    surface = LeaderManagementActionSurface.CORE,
                    outcome = result.outcome,
                    phase = LeaderManagementActionPhase.QUARANTINED,
                    mutationAttempted = result.mutationAttempted,
                    quarantined = true,
                    quarantineReason = reason,
                ),
            )
        } catch (_: Throwable) {
            // recovery observer failure is isolated from worker cleanup.
        }
    }

    private fun immediate(outcome: LeaderManagementActionOutcome): LeaderManagementActionResult {
        val result = result(outcome, false)
        try {
            observer?.onResult(
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
        return result
    }

    private fun result(outcome: LeaderManagementActionOutcome, mutationAttempted: Boolean) =
        LeaderManagementActionResult(LeaderManagementAction.RELEASE, outcome, mutationAttempted)

    private fun timeoutResult(action: SuspendLeaderManagementActionStore.ActionRecord) =
        result(
            LeaderManagementActionOutcome.ACTION_TIMED_OUT,
            action.mutationAttempted.get() || action.phase.get() in setOf(
                LeaderManagementActionPhase.RELEASE_STARTED,
                LeaderManagementActionPhase.POSTCHECK,
            ),
        )

    private fun quarantineReason(phase: LeaderManagementActionPhase) = when (phase) {
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

    private suspend fun CompletableDeferred<Unit>.awaitWithTimeout(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            await()
            true
        } ?: false
}

/** suspend registry의 non-blocking action state를 빠르게 선형화합니다. */
@Suppress("TooManyFunctions")
private class SuspendLeaderManagementActionStore(
    private val maxRegistrations: Int,
    private val maxInFlightActions: Int,
) {

    internal enum class Lifecycle { OPEN, QUIESCING, CLOSED }

    internal class RegistrationRecord(val handle: SuspendLeaderLeaseHandle) {
        val lockName: String = handle.lockName
        var registrationCount: Int = 0
        val actionInProgress = AtomicBoolean(false)
    }

    internal class ActionRecord(val registration: RegistrationRecord) {
        val phase = AtomicReference(LeaderManagementActionPhase.ADMITTED)
        val terminal = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)
        val mutationAttempted = AtomicBoolean(false)
        val workerStarted = AtomicBoolean(false)
        val watcherStarted = AtomicBoolean(false)
        val workerFinished = CompletableDeferred<Unit>()
        @Volatile var deferred: CompletableDeferred<LeaderManagementActionResult>? = null
        @Volatile var job: Job? = null
        @Volatile var result: LeaderManagementActionResult? = null
        @Volatile var quarantined: Boolean = false
        @Volatile var quarantineReason: LeaderManagementQuarantineReason? = null
    }

    internal sealed interface RegistrationDecision {
        data class Accepted(val record: RegistrationRecord) : RegistrationDecision
        data class Rejected(val outcome: LeaderManagementRegistrationOutcome) : RegistrationDecision
    }

    internal sealed interface Selection {
        data class Record(val value: RegistrationRecord) : Selection
        data object NotRegistered : Selection
        data object Ambiguous : Selection
        data object Closed : Selection
    }

    internal enum class BeginOutcome {
        STARTED,
        NOT_REGISTERED,
        AMBIGUOUS,
        ACTION_IN_PROGRESS,
        ACTION_ADMISSION_REJECTED,
        REGISTRY_CLOSED,
    }

    private val lock = ReentrantLock()
    private val byLockName = LinkedHashMap<String, MutableList<RegistrationRecord>>()
    private val byHandle = IdentityHashMap<SuspendLeaderLeaseHandle, RegistrationRecord>()
    private val activeActions = IdentityHashMap<ActionRecord, Boolean>()
    private val quarantinedActions = IdentityHashMap<ActionRecord, Boolean>()
    private var registrationCount = 0
    private var lifecycle = Lifecycle.OPEN

    internal fun register(handle: SuspendLeaderLeaseHandle): RegistrationDecision = lock.withLock {
        if (lifecycle != Lifecycle.OPEN) return@withLock RegistrationDecision.Rejected(
            LeaderManagementRegistrationOutcome.REGISTRY_CLOSED,
        )
        if (registrationCount >= maxRegistrations) return@withLock RegistrationDecision.Rejected(
            LeaderManagementRegistrationOutcome.CAPACITY_REJECTED,
        )
        val existing = byHandle[handle]
        if (existing != null) {
            existing.registrationCount++
            registrationCount++
            return@withLock RegistrationDecision.Accepted(existing)
        }
        val record = RegistrationRecord(handle).also { it.registrationCount = 1 }
        byHandle[handle] = record
        byLockName.getOrPut(record.lockName) { mutableListOf() }.add(record)
        registrationCount++
        RegistrationDecision.Accepted(record)
    }

    internal fun closeRegistration(record: RegistrationRecord) = lock.withLock {
        if (record.registrationCount <= 0) return@withLock
        record.registrationCount--
        registrationCount--
        removeIfDetached(record)
    }

    internal fun select(lockName: String): Selection = lock.withLock {
        val records = byLockName[lockName].orEmpty().filter { it.registrationCount > 0 }
        when {
            lifecycle != Lifecycle.OPEN -> Selection.Closed
            records.isEmpty() -> Selection.NotRegistered
            records.size > 1 -> Selection.Ambiguous
            else -> Selection.Record(records.single())
        }
    }

    internal fun begin(record: RegistrationRecord): Pair<BeginOutcome, ActionRecord?> = lock.withLock {
        if (lifecycle != Lifecycle.OPEN) return@withLock BeginOutcome.REGISTRY_CLOSED to null
        if (record.registrationCount <= 0 || byHandle[record.handle] !== record) {
            return@withLock BeginOutcome.NOT_REGISTERED to null
        }
        if (byLockName[record.lockName]?.count { it.registrationCount > 0 } != 1) {
            return@withLock BeginOutcome.AMBIGUOUS to null
        }
        if (!record.actionInProgress.compareAndSet(false, true)) {
            return@withLock BeginOutcome.ACTION_IN_PROGRESS to null
        }
        if (activeActions.size >= maxInFlightActions) {
            record.actionInProgress.set(false)
            return@withLock BeginOutcome.ACTION_ADMISSION_REJECTED to null
        }
        ActionRecord(record).also { activeActions[it] = true }.let { BeginOutcome.STARTED to it }
    }

    internal fun finish(action: ActionRecord) = lock.withLock {
        if (activeActions.remove(action) != null) action.registration.actionInProgress.set(false)
        quarantinedActions.remove(action)
        removeIfDetached(action.registration)
    }

    internal fun quarantine(action: ActionRecord) = lock.withLock {
        if (activeActions.containsKey(action)) {
            quarantinedActions[action] = true
            action.quarantined = true
        }
    }

    internal fun beginQuiescing(): Boolean = lock.withLock {
        if (lifecycle == Lifecycle.CLOSED) return@withLock false
        lifecycle = Lifecycle.QUIESCING
        true
    }

    internal fun closeLifecycle() = lock.withLock { lifecycle = Lifecycle.CLOSED }

    internal fun activeActionCount(): Int = lock.withLock { activeActions.size }

    internal fun activeActionRecords(): List<ActionRecord> = lock.withLock { activeActions.keys.toList() }

    internal fun quarantinedCount(): Int = lock.withLock { quarantinedActions.size }

    internal fun registeredLockNames(): List<String> = lock.withLock {
        byLockName.asSequence()
            .filter { (_, records) -> records.any { it.registrationCount > 0 } }
            .map { it.key }
            .sorted()
            .toList()
    }

    private fun removeIfDetached(record: RegistrationRecord) {
        if (record.registrationCount > 0 || record.actionInProgress.get()) return
        byHandle.remove(record.handle)
        byLockName[record.lockName]?.remove(record)
        if (byLockName[record.lockName].isNullOrEmpty()) byLockName.remove(record.lockName)
    }
}

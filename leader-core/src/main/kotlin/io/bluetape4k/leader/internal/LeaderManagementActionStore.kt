package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderManagementActionResult
import io.bluetape4k.leader.LeaderManagementActionPhase
import io.bluetape4k.leader.LeaderManagementQuarantineReason
import io.bluetape4k.leader.LeaderManagementRegistrationOutcome
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** blocking management registry의 identity, admission, lifecycle 상태를 소유합니다. */
internal class LeaderManagementActionStore(
    private val maxRegistrations: Int,
    private val maxActionReservations: Int,
) {

    internal enum class Lifecycle {
        OPEN,
        QUIESCING,
        CLOSED,
    }

    internal class RegistrationRecord(
        val handle: LeaderLeaseHandle,
    ) {
        val lockName: String = handle.lockName
        var registrationCount: Int = 0
        val actionInProgress = AtomicBoolean(false)
    }

    internal class ActionRecord(
        val registration: RegistrationRecord,
    ) {
        val phase = AtomicReference(LeaderManagementActionPhase.ADMITTED)
        val terminal = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val mutationAttempted = AtomicBoolean(false)
        val workerStarted = AtomicBoolean(false)
        val watcherStarted = AtomicBoolean(false)
        val workerFinished = CountDownLatch(1)
        @Volatile
        var future: Future<LeaderManagementActionResult>? = null
        @Volatile
        var result: LeaderManagementActionResult? = null
        @Volatile
        var quarantineReason: LeaderManagementQuarantineReason? = null
        @Volatile
        var quarantined: Boolean = false
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
    private val byHandle = IdentityHashMap<LeaderLeaseHandle, RegistrationRecord>()
    private val activeActions = IdentityHashMap<ActionRecord, Boolean>()
    private val quarantinedActions = IdentityHashMap<ActionRecord, Boolean>()
    private var registrationCount = 0
    private var lifecycle = Lifecycle.OPEN

    internal fun register(handle: LeaderLeaseHandle): RegistrationDecision = lock.withLock {
        if (lifecycle != Lifecycle.OPEN) return@withLock RegistrationDecision.Rejected(
            LeaderManagementRegistrationOutcome.REGISTRY_CLOSED,
        )
        if (registrationCount >= maxRegistrations) {
            return@withLock RegistrationDecision.Rejected(
                LeaderManagementRegistrationOutcome.CAPACITY_REJECTED,
            )
        }
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
        val activeRecords = byLockName[lockName].orEmpty().filter { it.registrationCount > 0 }
        when {
            lifecycle != Lifecycle.OPEN -> Selection.Closed
            activeRecords.isEmpty() -> Selection.NotRegistered
            activeRecords.size > 1 -> Selection.Ambiguous
            else -> Selection.Record(activeRecords.single())
        }
    }

    /** begin과 생성된 ActionRecord를 하나의 선형화 지점에서 반환합니다. */
    internal fun beginRecord(record: RegistrationRecord): Pair<BeginOutcome, ActionRecord?> = lock.withLock {
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
        if (activeActions.size >= maxActionReservations) {
            record.actionInProgress.set(false)
            return@withLock BeginOutcome.ACTION_ADMISSION_REJECTED to null
        }
        val action = ActionRecord(record)
        activeActions[action] = true
        BeginOutcome.STARTED to action
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

    internal fun lifecycle(): Lifecycle = lock.withLock { lifecycle }

    internal fun beginQuiescing(): Boolean = lock.withLock {
        if (lifecycle == Lifecycle.CLOSED) return@withLock false
        lifecycle = Lifecycle.QUIESCING
        true
    }

    internal fun closeLifecycle() = lock.withLock {
        lifecycle = Lifecycle.CLOSED
    }

    internal fun activeActionCount(): Int = lock.withLock { activeActions.size }

    internal fun quarantinedCount(): Int = lock.withLock { quarantinedActions.size }

    internal fun activeActionRecords(): List<ActionRecord> = lock.withLock { activeActions.keys.toList() }

    internal fun registeredLockNames(): List<String> = lock.withLock {
        byLockName.asSequence()
            .filter { (_, records) -> records.any { it.registrationCount > 0 } }
            .map { it.key }
            .sorted()
            .toList()
    }

    internal fun <T> withState(block: () -> T): T = lock.withLock(block)

    private fun removeIfDetached(record: RegistrationRecord) {
        if (record.registrationCount > 0 || record.actionInProgress.get()) return
        byHandle.remove(record.handle)
        byLockName[record.lockName]?.remove(record)
        if (byLockName[record.lockName].isNullOrEmpty()) byLockName.remove(record.lockName)
    }
}

package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.local.AbstractLocalLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Local (single-JVM) suspend multi-leader election implementation using coroutine [Semaphore].
 *
 * ## Behavior
 * - Creates a `kotlinx.coroutines.sync.Semaphore(maxLeaders)` per `lockName` to limit concurrent executions.
 * - If all slots are full, the calling coroutine suspends until a slot becomes available.
 * - Slots are managed via [Semaphore.withPermit] and are always released even when an exception occurs.
 * - Suitable for limiting concurrent coroutine execution within a single JVM process, not distributed environments.
 *
 * ## Difference from [io.bluetape4k.leader.local.LocalLeaderGroupElector]
 * - [io.bluetape4k.leader.local.LocalLeaderGroupElector] uses `java.util.concurrent.Semaphore` (thread blocking).
 * - This implementation uses `kotlinx.coroutines.sync.Semaphore` (coroutine suspend).
 *
 * ```kotlin
 * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // up to 3 coroutines run concurrently
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 *
 * // state query
 * println(election.state("batch-job"))
 * ```
 *
 * @param options leader group election options. Default is [LeaderGroupElectionOptions.Default]
 */
class LocalSuspendLeaderGroupElector private constructor(
    private val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    companion object: KLogging() {
        /**
         * Creates a [LocalSuspendLeaderGroupElector] instance with the given [LeaderGroupElectionOptions].
         *
         * ```kotlin
         * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runIfLeader("batch-job") { "done" }
         * // result == "done"
         * ```
         *
         * @param options leader group election options. Default is [LeaderGroupElectionOptions.Default]
         * @return a [LocalSuspendLeaderGroupElector] instance
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): LocalSuspendLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalSuspendLeaderGroupElector)
    }

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) { Semaphore(maxLeaders) }
    }

    override val maxLeaders: Int = options.maxLeaders

    /**
     * Returns the number of currently active (running) leaders for [lockName].
     *
     * Computed as `maxLeaders - availablePermits`, so the value is approximate.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val count = election.activeCount("batch-job")
     * // count == 0  (when no leader is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return current active leader count (approximate)
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits

    /**
     * Returns the number of remaining slots available to accept new leaders for [lockName].
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val slots = election.availableSlots("batch-job")
     * // slots == 3  (when no leader is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return number of available slots (approximate)
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits

    /**
     * Returns a snapshot of the current [LeaderGroupState] for [lockName].
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val state = election.state("batch-job")
     * // state.maxLeaders == 3
     * // state.activeCount == 0
     * // state.isEmpty == true
     * ```
     *
     * @param lockName the lock name to query
     * @return current leader group state snapshot
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * Acquires a [Semaphore] slot for [lockName] and runs suspend [action].
     *
     * - If all slots are full, the coroutine suspends until a slot becomes available.
     * - The slot is always released even if [action] throws.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runIfLeader("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName the lock name used for leader group election
     * @param action the suspend action to run when a slot is acquired
     * @return [action] result, or `null` if the slot was not acquired
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        tryWithPermit(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     *
     * Cancellation: rethrows `CancellationException` directly; no `runCatching` around suspend calls.
     */
    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     *
     * Cancellation: rethrows `CancellationException` directly; no `runCatching` around suspend calls.
     */
    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
            ) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private suspend fun <T> tryWithPermit(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: suspend () -> T,
    ): T? {
        val semaphore = getSemaphore(lockName)
        // withTimeoutOrNull 은 semaphore 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            semaphore.acquire()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireGroup(
            lockName,
            auditLeaderId = auditLeaderId,
            nodeId = nodeId,
            leaseTime = options.leaseTime,
            maxLeaders = maxLeaders,
        )
        val slot = requireNotNull(lease.slot) {
            "Group lease.slot must be non-null for lockName=$lockName, kind=GROUP"
        }

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = AbstractLocalLeaderGroupElector.LOCAL_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadlineRef
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome {
                val extended = states.extendGroup(lockName, slot, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                } else {
                    ExtendOutcome.NotHeld
                }
            }
            override fun isHeld(): Boolean = states.isSlotHeld(lockName, slot)
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate)
        listeners.notifyElected(lockName)
        eventSubject.emit(LeaderElectionEvent.Elected(lockName, leaderId = auditLeaderId))
        return try {
            withContext(LockHandleElement(handle)) {
                action()
            }
        } finally {
            withContext(NonCancellable) {
                watchdog.close()
                delayRemainingMinLeaseTime(startedAtNanos)
                states.releaseGroup(lockName, lease)
                if (acquired) semaphore.release()
                listeners.notifyRevoked(lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(lockName))
            }
        }
    }

    private suspend fun delayRemainingMinLeaseTime(startedAtNanos: Long) {
        val remaining = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
        if (remaining > kotlin.time.Duration.ZERO) {
            delay(remaining)
        }
    }
}

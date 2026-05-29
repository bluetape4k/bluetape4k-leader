package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionState
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CaptureScope
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract class providing common state management for local (single-JVM) leader group election implementations.
 *
 * ## Role
 * - Manages a pool of per-lockName [Semaphore] instances using [ConcurrentHashMap].
 * - Implements [LeaderGroupElectionState] state query methods ([activeCount], [availableSlots], [state]).
 * - Subclasses use [getSemaphore] to acquire/release slots and implement execution logic.
 *
 * ## Subclasses
 * - [LocalLeaderGroupElector]: synchronous + async ([java.util.concurrent.CompletableFuture]) execution
 * - [LocalAsyncLeaderGroupElector]: async ([java.util.concurrent.CompletableFuture]) execution only
 * - [LocalVirtualThreadLeaderGroupElector]: [io.bluetape4k.concurrent.virtualthread.VirtualFuture] execution
 *
 * @param options leader group election options (maxLeaders, waitTime, leaseTime). Default is [LeaderGroupElectionOptions.Default].
 */
abstract class AbstractLocalLeaderGroupElector(
    protected val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElectionState, LeaderElectionListenerRegistry {

    companion object {
        /** Constant for [LockIdentity.factoryBeanName] diagnostic metadata — Local backend group. */
        internal const val LOCAL_GROUP_FACTORY_BEAN_NAME = "local-leader-group-elector"
    }

    init {
        options.maxLeaders.requirePositiveNumber("maxLeaders")
    }

    override val maxLeaders: Int = options.maxLeaders

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val listeners = LeaderElectionListenerSupport()
    private val states = LocalLeaderStateRegistry()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    /**
     * Returns the [Semaphore] for [lockName], creating `Semaphore(maxLeaders, fair=true)` if it does not exist.
     *
     * ```kotlin
     * val semaphore = getSemaphore("batch-job")
     * semaphore.acquire()
     * try { /* critical section */ } finally { semaphore.release() }
     * ```
     *
     * @param lockName the lock name (must not be blank)
     * @return the [Semaphore] instance for the given lockName
     */
    protected fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) { Semaphore(maxLeaders, true) }
    }

    /**
     * Executes [action] while holding a slot for [lockName] and releases the slot on completion.
     *
     * ```kotlin
     * val result = withPermit("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName the lock name
     * @param action the action to run while holding the slot
     * @return [action] result
     */
    protected fun <T> withPermit(lockName: String, action: () -> T): T {
        val semaphore = getSemaphore(lockName)
        semaphore.acquire()
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }

    /**
     * Executes [action] if a slot for [lockName] is acquired within the configured wait time; returns `null` if not.
     *
     * ```kotlin
     * val result = tryWithPermit("batch-job") { "done" }
     * // result == "done" (slot acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name
     * @param action the action to run when the slot is acquired
     * @return [action] result, or `null` if the slot was not acquired
     */
    protected fun <T> tryWithPermit(lockName: String, action: () -> T): T? =
        tryWithPermit(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * Executes [action] if a slot for [lockName] is acquired; returns `null` if not.
     *
     * Slot-aware overload — stamps [auditLeaderId] (typically `LeaderSlot.leaderId`) into the
     * [LeaderLease.auditLeaderId] / [LeaderLockHandle.Real.auditLeaderId] for audit traceability,
     * and the optional [nodeId] into [LeaderLease.nodeId].
     *
     * @param lockName the lock name
     * @param auditLeaderId stamped as `LeaderLease.auditLeaderId` and `LeaderLockHandle.Real.auditLeaderId`
     * @param nodeId stamped as `LeaderLease.nodeId`; defaults to `options.nodeId`
     * @param action the action to run when the slot is acquired
     * @return [action] result, or `null` if the slot was not acquired
     */
    protected fun <T> tryWithPermit(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: () -> T,
    ): T? {
        val semaphore = getSemaphore(lockName)
        val acquired = semaphore.tryAcquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!acquired) {
            listeners.notifySkipped(lockName)
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
            factoryBeanName = LOCAL_GROUP_FACTORY_BEAN_NAME,
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
        listeners.notifyElected(lockName, lease)
        return try {
            LockStateHolder.withPushed(handle) {
                CaptureScope.runWithCapture(handle) {
                    action()
                }
            }
        } finally {
            watchdog.close()
            parkRemainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
            states.releaseGroup(lockName, lease)
            semaphore.release()
            listeners.notifyRevoked(lockName)
        }
    }

    /**
     * Returns the number of currently active (running) leaders for [lockName].
     *
     * ```kotlin
     * val count = activeCount("batch-job")
     * // count == 0  (when no leader is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return current active leader count (approximate)
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits()

    /**
     * Returns the number of remaining slots available to accept new leaders for [lockName].
     *
     * ```kotlin
     * val slots = availableSlots("batch-job")
     * // slots == maxLeaders  (when no leader is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return number of available slots (approximate)
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits()

    /**
     * Returns a snapshot of the current [LeaderGroupState] for [lockName].
     *
     * ```kotlin
     * val state = state("batch-job")
     * // state.maxLeaders == maxLeaders
     * // state.activeCount == 0
     * // state.isEmpty == true
     * ```
     *
     * @param lockName the lock name to query
     * @return current leader group state snapshot
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))
}

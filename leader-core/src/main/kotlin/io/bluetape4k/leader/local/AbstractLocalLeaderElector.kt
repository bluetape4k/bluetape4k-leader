package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration

/**
 * Abstract class providing common lock management for local (single-JVM) leader election implementations.
 *
 * ## Role
 * - Manages a pool of per-lockName [ReentrantLock] instances using [ConcurrentHashMap].
 * - Subclasses use [getLock] to acquire/release locks and implement execution logic.
 *
 * ## Subclasses
 * - [LocalLeaderElector]: synchronous + async ([java.util.concurrent.CompletableFuture]) execution
 * - [LocalAsyncLeaderElector]: async ([java.util.concurrent.CompletableFuture]) execution only
 * - [LocalVirtualThreadLeaderElector]: [io.bluetape4k.concurrent.virtualthread.VirtualFuture] execution
 */
abstract class AbstractLocalLeaderElector(
    protected val options: LeaderElectionOptions = LeaderElectionOptions.Default,
) : LeaderElectionListenerRegistry, LeaderElectionState {

    companion object {
        /** Constant for [LockIdentity.factoryBeanName] diagnostic metadata — Local backend. */
        internal const val LOCAL_FACTORY_BEAN_NAME = "local-leader-elector"
    }

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val listeners = LeaderElectionListenerSupport()
    private val states = LocalLeaderStateRegistry()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    /**
     * Returns the [ReentrantLock] for [lockName], creating a new one if it does not exist.
     *
     * ```kotlin
     * val lock = getLock("job-lock")
     * lock.withLock { /* critical section */ }
     * ```
     *
     * @param lockName the lock name (must not be blank)
     * @return the [ReentrantLock] instance for the given lockName
     */
    protected fun getLock(lockName: String): ReentrantLock {
        lockName.requireNotBlank("lockName")
        return locks.computeIfAbsent(lockName) { ReentrantLock() }
    }

    /**
     * Executes [action] while holding the lock for [lockName].
     *
     * ```kotlin
     * val result = withLeaderLock("job-lock") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName the lock name
     * @param action the action to run while holding the lock
     * @return [action] result
     */
    protected fun <T> withLeaderLock(lockName: String, action: () -> T): T {
        val lock = getLock(lockName)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Executes [action] if the lock for [lockName] is acquired within [waitTime]; returns `null` if not.
     *
     * ```kotlin
     * val result = tryWithLeaderLock("job-lock", Duration.ofSeconds(1)) { "done" }
     * // result == "done" (lock acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name
     * @param waitTime maximum wait time for lock acquisition
     * @param action the action to run when the lock is acquired
     * @return [action] result, or `null` if the lock was not acquired
     */
    protected fun <T> tryWithLeaderLock(lockName: String, waitTime: Duration, action: () -> T): T? =
        tryWithLeaderLock(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            waitTime = waitTime,
            action = action,
        )

    /**
     * Executes [action] if the lock for [lockName] is acquired within [waitTime]; returns `null` if not.
     *
     * Slot-aware overload — stamps [auditLeaderId] (typically `LeaderSlot.leaderId`) into the
     * [LeaderLease.auditLeaderId] / [LeaderLockHandle.Real.auditLeaderId] for audit traceability,
     * and the optional [nodeId] into [LeaderLease.nodeId].
     *
     * @param lockName the lock name
     * @param auditLeaderId stamped as `LeaderLease.auditLeaderId` and `LeaderLockHandle.Real.auditLeaderId`
     * @param nodeId stamped as `LeaderLease.nodeId`; defaults to `options.nodeId`
     * @param waitTime maximum wait time for lock acquisition
     * @param action the action to run when the lock is acquired
     * @return [action] result, or `null` if the lock was not acquired
     */
    protected fun <T> tryWithLeaderLock(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        waitTime: Duration,
        action: () -> T,
    ): T? {
        val lock = getLock(lockName)

        // Reentrant: same thread holds the lock — wrap in a passthrough handle
        val existing = LockStateHolder.peekSyncMatching(lockName)
        if (lock.isHeldByCurrentThread && existing is LeaderLockHandle.Real) {
            val reentrant = existing.withReentryDepth(existing.reentryDepth + 1)
            return LockStateHolder.withPushed(reentrant) { action() }
        }

        val acquired = lock.tryLock(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!acquired) {
            listeners.notifySkipped(lockName)
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireSingle(lockName, auditLeaderId = auditLeaderId, nodeId = nodeId, leaseTime = options.leaseTime)

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LOCAL_FACTORY_BEAN_NAME,
        )
        val lastExtendDeadline = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadline
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: Duration): io.bluetape4k.leader.ExtendOutcome {
                val extended = states.extendSingle(lockName, lockAtMostFor)
                return if (extended) {
                    io.bluetape4k.leader.ExtendOutcome.Extended(
                        Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
                    )
                } else {
                    io.bluetape4k.leader.ExtendOutcome.NotHeld
                }
            }
            override fun isHeld(): Boolean = states.singleState(lockName).isOccupied
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
        listeners.notifyElected(lockName, lease)
        return try {
            LockStateHolder.withPushed(handle) { action() }
        } finally {
            watchdog.close()
            parkRemainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
            states.releaseSingle(lockName)
            lock.unlock()
            listeners.notifyRevoked(lockName)
        }
    }

    override fun state(lockName: String): LeaderState =
        states.singleState(lockName)
}

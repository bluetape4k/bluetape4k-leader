package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.local.AbstractLocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Local (single-JVM) suspend leader election implementation using coroutines [Mutex].
 *
 * ## Behavior
 * - Guarantees serial execution via mutual exclusion between coroutines for the same `lockName`.
 * - The coroutine that acquires the [Mutex] runs `action` as leader; other coroutines suspend until it is released.
 * - Suitable for serializing concurrent coroutine execution within a single JVM process, not a distributed environment.
 *
 * ## Warning
 * - [Mutex] does not support re-entrancy.
 *   Nested calls with the same `lockName` from the same coroutine will cause a deadlock.
 *   If re-entrancy is needed, use [io.bluetape4k.leader.local.LocalLeaderElector] (based on [java.util.concurrent.locks.ReentrantLock]).
 *
 * ```kotlin
 * val election = LocalSuspendLeaderElector()
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 */
class LocalSuspendLeaderElector(
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): SuspendLeaderElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getMutex(lockName: String): Mutex {
        lockName.requireNotBlank("lockName")
        return mutexes.computeIfAbsent(lockName) { Mutex() }
    }

    /**
     * Acquires the [Mutex] for [lockName] and executes [action] serially.
     *
     * If another coroutine holds the [Mutex] for the same [lockName], this coroutine suspends until it is released.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderElector()
     * val result = election.runIfLeader("job-lock") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the suspend action to run when leader acquisition succeeds
     * @return the [action] result, or `null` when leader acquisition fails
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        tryWithLock(
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
        tryWithLock(
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
            tryWithLock(
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

    private suspend fun <T> tryWithLock(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: suspend () -> T,
    ): T? {
        val mutex = getMutex(lockName)
        // withTimeoutOrNull 은 lock 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            mutex.lock()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        states.acquireSingle(lockName, auditLeaderId = auditLeaderId, nodeId = nodeId, leaseTime = options.leaseTime)

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = AbstractLocalLeaderElector.LOCAL_FACTORY_BEAN_NAME,
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadlineRef
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome {
                val extended = states.extendSingle(lockName, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                } else {
                    ExtendOutcome.NotHeld
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
                states.releaseSingle(lockName)
                if (acquired) mutex.unlock()
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

    override fun state(lockName: String): LeaderState =
        states.singleState(lockName)
}

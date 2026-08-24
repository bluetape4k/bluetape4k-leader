package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLeaseDefaults
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.bluetape4k.support.requireGt
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** backend와 무관하게 non-cancellable bounded cleanup을 수행하는 coroutine lifecycle입니다. */
internal class SuspendLeaderLeaseLifecycle(
    private val options: LeaderElectionOptions,
    private val callbacks: SuspendLeaseBackendCallbacks,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    suspend fun tryAcquire(slot: LeaderSlot, waitTime: Duration = options.waitTime): SuspendLeaderLeaseHandle? {
        val now = monotonicNanos()
        val deadline = safePlus(now, waitTime.inWholeNanoseconds.coerceAtLeast(0L))
        val backend = callbacks.acquire(slot, deadline, deadline) ?: return null
        return ManagedSuspendLeaseHandle(backend, options, callbacks, monotonicNanos)
    }

    private class ManagedSuspendLeaseHandle(
        private val backend: BackendLease,
        private val options: LeaderElectionOptions,
        private val callbacks: SuspendLeaseBackendCallbacks,
        private val monotonicNanos: () -> Long,
    ) : SuspendLeaderLeaseHandle {
        private enum class State { LIVE, CLOSING, CLOSED }

        private val state = AtomicReference(State.LIVE)
        private val status = AtomicReference(LeaseOwnershipStatus.HELD)
        private val watchdog: AutoCloseable

        override val lockName: String get() = backend.slot.lockName
        override val auditLeaderId: String get() = backend.slot.leaderId
        override val acquiredAt: Instant get() = backend.acquiredAt

        init {
            val delegate = object : SuspendExtendDelegate {
                override val lastExtendDeadline = AtomicReference(Instant.EPOCH)
                override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)
                override suspend fun isHeldSuspend(): Boolean = isStillHeld()
            }
            watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome {
            if (state.get() != State.LIVE) return ExtendOutcome.NotHeld
            lockAtMostFor.requireGt(Duration.ZERO, "lockAtMostFor")
            return try {
                callbacks.extend(backend, lockAtMostFor.coerceAtMost(options.leaseTime), releaseDeadline()).also {
                    if (it is ExtendOutcome.NotHeld) status.set(LeaseOwnershipStatus.NOT_HELD)
                }
            } catch (ex: Exception) {
                status.set(LeaseOwnershipStatus.UNKNOWN)
                ExtendOutcome.BackendError(ex)
            }
        }

        override suspend fun ownershipStatus(): LeaseOwnershipStatus {
            if (state.get() != State.LIVE) return status.get()
            return try {
                callbacks.isHeld(backend, releaseDeadline()).also { status.set(it) }
            } catch (_: Exception) {
                status.set(LeaseOwnershipStatus.UNKNOWN)
                LeaseOwnershipStatus.UNKNOWN
            }
        }

        override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override suspend fun release() = withContext(NonCancellable) {
            if (!state.compareAndSet(State.LIVE, State.CLOSING)) return@withContext
            val deadline = releaseDeadline()
            try {
                if (options.autoExtend) runCatching { callbacks.stopWatchdog(backend, deadline) }
                watchdog.close()
                val remaining = (
                    options.minLeaseTime.inWholeNanoseconds -
                        (monotonicNanos() - backend.acquiredAtNanos)
                    )
                    .coerceAtLeast(0L)
                if (remaining > 0L) delay(remaining / NANOS_PER_MILLISECOND)
                when (runCatching { callbacks.release(backend, deadline) }.getOrElse { BackendReleaseOutcome.ERROR }) {
                    BackendReleaseOutcome.RELEASED, BackendReleaseOutcome.NOT_HELD ->
                        status.set(LeaseOwnershipStatus.NOT_HELD)
                    BackendReleaseOutcome.ERROR, BackendReleaseOutcome.TIMEOUT ->
                        status.set(LeaseOwnershipStatus.UNKNOWN)
                }
            } finally {
                state.set(State.CLOSED)
            }
        }

        private fun releaseDeadline(): Long = safePlus(
            monotonicNanos(),
            minOf(LeaderLeaseDefaults.PUBLIC_RELEASE_TIMEOUT, options.leaseTime).inWholeNanoseconds,
        )

        private fun safePlus(first: Long, second: Long): Long =
            if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
    }

    private fun safePlus(first: Long, second: Long): Long =
        if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

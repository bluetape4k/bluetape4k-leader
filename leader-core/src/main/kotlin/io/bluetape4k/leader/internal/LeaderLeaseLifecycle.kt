package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaseCleanupBoundary
import io.bluetape4k.leader.LeaseCleanupReservation
import io.bluetape4k.leader.LeaseCleanupResult
import io.bluetape4k.leader.LeaderLeaseDefaults
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.support.requireGt
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** backend와 무관하게 acquire/watchdog/min-lease/release를 수행하는 동기 lifecycle입니다. */
internal class LeaderLeaseLifecycle(
    private val options: LeaderElectionOptions,
    private val callbacks: LeaseBackendCallbacks,
    private val cleanupBoundary: LeaseCleanupBoundary? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    fun tryAcquire(slot: LeaderSlot, waitTime: Duration = options.waitTime): LeaderLeaseHandle? {
        val now = monotonicNanos()
        val waitDeadline = safePlus(now, waitTime.inWholeNanoseconds.coerceAtLeast(0L))
        val backend = callbacks.acquire(slot, waitDeadline, waitDeadline) ?: return null
        return ManagedLeaseHandle(backend, options, callbacks, cleanupBoundary, monotonicNanos)
    }

    private class ManagedLeaseHandle(
        private val backend: BackendLease,
        private val options: LeaderElectionOptions,
        private val callbacks: LeaseBackendCallbacks,
        private val cleanupBoundary: LeaseCleanupBoundary?,
        private val monotonicNanos: () -> Long,
    ) : LeaderLeaseHandle {
        private enum class State { LIVE, CLOSING, CLOSED }

        private val state = AtomicReference(State.LIVE)
        private val status = AtomicReference(LeaseOwnershipStatus.HELD)
        private val watchdog: AutoCloseable

        override val lockName: String get() = backend.slot.lockName
        override val auditLeaderId: String get() = backend.slot.leaderId
        override val acquiredAt: Instant get() = backend.acquiredAt

        init {
            val delegate = object : ExtendDelegate {
                override val lastExtendDeadline = AtomicReference(Instant.EPOCH)
                override fun extend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)
                override fun isHeld(): Boolean = isStillHeld()
            }
            watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
        }

        @Suppress("TooGenericExceptionCaught")
        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            if (state.get() != State.LIVE) return ExtendOutcome.NotHeld
            lockAtMostFor.requireGt(Duration.ZERO, "lockAtMostFor")
            val bounded = lockAtMostFor.coerceAtMost(options.leaseTime)
            return try {
                callbacks.extend(backend, bounded, releaseDeadline()).also { outcome ->
                    if (outcome is ExtendOutcome.NotHeld) status.set(LeaseOwnershipStatus.NOT_HELD)
                }
            } catch (ex: Exception) {
                status.set(LeaseOwnershipStatus.UNKNOWN)
                ExtendOutcome.BackendError(ex)
            }
        }

        override fun ownershipStatus(): LeaseOwnershipStatus {
            if (state.get() != State.LIVE) return status.get()
            return try {
                callbacks.isHeld(backend, releaseDeadline()).also { status.set(it) }
            } catch (_: Exception) {
                status.set(LeaseOwnershipStatus.UNKNOWN)
                LeaseOwnershipStatus.UNKNOWN
            }
        }

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() {
            if (!state.compareAndSet(State.LIVE, State.CLOSING)) return
            val deadline = releaseDeadline()
            try {
                if (options.autoExtend) {
                    try {
                        callbacks.stopWatchdog(backend, deadline)
                    } catch (_: Exception) {
                        // stop 실패는 release도 실패한 경우에만 UNKNOWN으로 관찰합니다.
                    }
                }
                watchdog.close()
                parkRemainingMinLeaseTime(backend.acquiredAtNanos, options.minLeaseTime)
                val result = cleanupBoundary?.releaseWithin(
                    (deadline - monotonicNanos()).coerceAtLeast(0L).nanoseconds,
                    NoopReservation,
                ) ?: runCatching { callbacks.release(backend, deadline) }
                    .getOrElse { BackendReleaseOutcome.ERROR }
                when (result) {
                    LeaseCleanupResult.RELEASED, BackendReleaseOutcome.RELEASED ->
                        status.set(LeaseOwnershipStatus.NOT_HELD)
                    LeaseCleanupResult.NOT_HELD, BackendReleaseOutcome.NOT_HELD ->
                        status.set(LeaseOwnershipStatus.NOT_HELD)
                    LeaseCleanupResult.RESIDUAL_TRANSFERRED, BackendReleaseOutcome.ERROR,
                    BackendReleaseOutcome.TIMEOUT ->
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

    private object NoopReservation : LeaseCleanupReservation {
        override val isTerminal: Boolean = false
        override fun terminalize() = Unit
    }

    private fun safePlus(first: Long, second: Long): Long =
        if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

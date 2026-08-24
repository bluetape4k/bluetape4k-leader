package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderSlot
import java.time.Instant
import kotlin.time.Duration

/** 동기·coroutine lifecycle helper가 공유하는 내부 backend callback 표입니다. */
interface LeaseBackendCallbacks {
    fun acquire(slot: LeaderSlot, waitDeadlineNanos: Long, transportDeadlineNanos: Long): BackendLease?

    fun extend(lease: BackendLease, duration: Duration, deadlineNanos: Long): ExtendOutcome

    fun release(lease: BackendLease, deadlineNanos: Long): BackendReleaseOutcome

    fun isHeld(lease: BackendLease, deadlineNanos: Long): LeaseOwnershipStatus

    fun stopWatchdog(lease: BackendLease, deadlineNanos: Long): BackendWatchdogOutcome =
        BackendWatchdogOutcome.NOT_RUNNING
}

/** 공개 handle 표면으로 token/generation을 노출하지 않는 내부 backend lease identity입니다. */
class BackendLease internal constructor(
    val slot: LeaderSlot,
    val acquiredAt: Instant,
    val acquiredAtNanos: Long,
    internal val generation: Long = GENERATION.incrementAndGet(),
) {
    private companion object {
        val GENERATION = java.util.concurrent.atomic.AtomicLong()
    }
}

enum class BackendReleaseOutcome { RELEASED, NOT_HELD, ERROR, TIMEOUT }

enum class BackendWatchdogOutcome { STOPPED, NOT_RUNNING, ERROR, TIMEOUT }

package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderSlot
import kotlin.time.Duration

/** [LeaseBackendCallbacks]의 coroutine 대응 callback 표입니다. */
interface SuspendLeaseBackendCallbacks {
    suspend fun acquire(slot: LeaderSlot, waitDeadlineNanos: Long, transportDeadlineNanos: Long): BackendLease?

    suspend fun extend(lease: BackendLease, duration: Duration, deadlineNanos: Long): ExtendOutcome

    suspend fun release(lease: BackendLease, deadlineNanos: Long): BackendReleaseOutcome

    suspend fun isHeld(lease: BackendLease, deadlineNanos: Long): LeaseOwnershipStatus

    suspend fun stopWatchdog(lease: BackendLease, deadlineNanos: Long): BackendWatchdogOutcome =
        BackendWatchdogOutcome.NOT_RUNNING
}

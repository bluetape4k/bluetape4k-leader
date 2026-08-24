package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot

/** Delegation surface for built-in suspend electors and wrappers. */
interface SuspendLeaderLeaseAcquirerSupport : SuspendLeaderLeaseAcquirer {
    val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer

    /** delegate가 실제 suspend request-lease capability를 제공하는지 selector가 확인합니다. */
    val leaseCapabilityAvailable: Boolean
        get() = true

    override val configuredOptions: LeaderElectionOptions
        get() = suspendLeaseAcquirerDelegate.configuredOptions

    override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? =
        suspendLeaseAcquirerDelegate.tryAcquire(lockName)

    override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? =
        suspendLeaseAcquirerDelegate.tryAcquire(slot)
}

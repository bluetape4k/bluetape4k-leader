package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

internal class FakeSuspendLeaderElector(
    private val stateValue: LeaderState = LeaderState.empty("job"),
    private val stateReads: AtomicInteger? = null,
    override val supportsAuditLeaderState: Boolean = true,
) : SuspendLeaderElector {
    override fun state(lockName: String): LeaderState {
        stateReads?.incrementAndGet()
        return stateValue.copy(lockName = lockName)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
}

internal class AutoCloseableFakeSuspendLeaderElector(
    private val delegate: FakeSuspendLeaderElector = FakeSuspendLeaderElector(),
) : SuspendLeaderElector, AutoCloseable {
    val closeCount = AtomicInteger()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun state(lockName: String): LeaderState = delegate.state(lockName)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        delegate.runIfLeader(lockName, action)

    override fun close() {
        closeCount.incrementAndGet()
    }
}

internal class TrackingLeaseHandle(
    override val lockName: String = "job",
    private val released: AtomicInteger = AtomicInteger(),
    private val releaseAction: suspend () -> Unit = {},
) : SuspendLeaderLeaseHandle {
    override val auditLeaderId: String = "test-node"
    override val acquiredAt: Instant = Instant.now()
    val releaseCount: Int get() = released.get()

    override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Rejected

    override suspend fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD

    override suspend fun isStillHeld(): Boolean = true

    override suspend fun release() {
        released.incrementAndGet()
        releaseAction()
    }
}

internal class CountingLeaseAcquirer(
    private val handle: SuspendLeaderLeaseHandle?,
) : SuspendLeaderLeaseAcquirer {
    override val configuredOptions: LeaderElectionOptions = LeaderElectionOptions()
    val acquireCount = AtomicInteger()

    override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? {
        acquireCount.incrementAndGet()
        return handle
    }

    override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? =
        tryAcquire(slot.lockName)
}

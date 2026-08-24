package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseWatchdogAdmission
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.local.LocalLeaderElector
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.coroutineContext

class LeaderElectorLeaseAdapterTest {

    @Test
    fun `blocking adapter preserves caller slot and delegates lifecycle`() {
        val options = LeaderElectionOptions(
            waitTime = 2.seconds,
            leaseTime = 1.seconds,
            nodeId = "backend-node",
        )
        val slot = LeaderSlot("adapter-lock", "request-node")
        val adapter = LeaderElectorLeaseAdapter({ LocalLeaderElector(options) }, options)

        val handle = adapter.tryAcquire(slot)
        handle!!.auditLeaderId shouldBeEqualTo "request-node"
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.HELD
        handle.extend(500.milliseconds).let { it is io.bluetape4k.leader.ExtendOutcome.Extended }.shouldBeTrue()
        handle.release()
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
    }

    @Test
    fun `suspend adapter releases cross coroutine`() = runSuspendIO {
        val options = LeaderElectionOptions(
            waitTime = 2.seconds,
            leaseTime = 1.seconds,
            nodeId = "backend-node",
        )
        val slot = LeaderSlot("suspend-adapter-lock", "request-node")
        val adapter = SuspendLeaderElectorLeaseAdapter({ LocalSuspendLeaderElector(options) }, options)

        val handle = adapter.tryAcquire(slot)
        handle!!.auditLeaderId shouldBeEqualTo "request-node"
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.HELD
        handle.release()
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
        adapter.close()
    }

    @Test
    fun `local suspend action exposes lock handle context`() = runSuspendIO {
        val options = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 1.seconds)
        val local = LocalSuspendLeaderElector(options)
        val present = local.runIfLeader(LeaderSlot("context-lock", "request-node")) {
            currentContextHasLockHandle()
        }
        present shouldBeEqualTo true
    }

    @Test
    fun `blocking adapter carries watchdog admission into backend owner thread`() {
        val options = LeaderElectionOptions(
            waitTime = 2.seconds,
            leaseTime = 90.milliseconds,
            autoExtend = true,
        )
        val rejectedTicks = AtomicInteger()
        val adapter = LeaderElectorLeaseAdapter({ LocalLeaderElector(options) }, options)
        val handle = LeaderLeaseWatchdogAdmission.withProvider(
            admission = {
                rejectedTicks.incrementAndGet()
                null
            },
            block = { adapter.tryAcquire(LeaderSlot("admission-lock", "request-node")) },
        )

        Thread.sleep(180)
        handle.shouldNotBeNull().release()

        (rejectedTicks.get() > 0).shouldBeTrue()
    }

    private suspend fun currentContextHasLockHandle(): Boolean =
        coroutineContext[LockHandleElement] != null
}

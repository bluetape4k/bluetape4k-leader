package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseExtensionEvent
import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.LeaderLeaseExtensionSource
import io.bluetape4k.leader.LeaderLeaseWatchdogAdmission
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.leaderLeaseExtensionDispatcher
import kotlinx.coroutines.withContext
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.coroutineContext

class LeaderElectorLeaseAdapterTest {

    @Test
    fun `blocking and suspend adapters carry observation scope to backend owner`() = runSuspendIO {
        val previous = leaderLeaseExtensionDispatcher
        val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val aEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val bEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
        val a = LeaderLeaseExtensionObservers.addScopedObserver(aEvents::add)
        val b = LeaderLeaseExtensionObservers.addScopedObserver(bEvents::add)
        val options = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 90.milliseconds, autoExtend = true)
        val blocking = LeaderElectorLeaseAdapter({ LocalLeaderElector(options) }, options)
        val suspend = SuspendLeaderElectorLeaseAdapter({ LocalSuspendLeaderElector(options) }, options)

        try {
            val blockingHandle = a.withScope {
                blocking.tryAcquire(LeaderSlot("scoped-blocking-adapter", "request-node"))
            }.shouldNotBeNull()
            val suspendHandle = withContext(a.asContextElement()) {
                suspend.tryAcquire(LeaderSlot("scoped-suspend-adapter", "request-node"))
            }.shouldNotBeNull()

            await.atMost(5.seconds).untilAsserted {
                (globalEvents.count { it.source == LeaderLeaseExtensionSource.WATCHDOG } > 0).shouldBeTrue()
                (aEvents.count { it.source == LeaderLeaseExtensionSource.WATCHDOG } > 0).shouldBeTrue()
            }
            bEvents.size shouldBeEqualTo 0
            blockingHandle.release()
            suspendHandle.release()
        } finally {
            suspend.close()
            global.close()
            a.close()
            b.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `direct adapter acquisition remains unscoped while global observer receives watchdog`() = runSuspendIO {
        val previous = leaderLeaseExtensionDispatcher
        val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val scopedEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
        val scoped = LeaderLeaseExtensionObservers.addScopedObserver(scopedEvents::add)
        val options = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 90.milliseconds, autoExtend = true)
        val blocking = LeaderElectorLeaseAdapter({ LocalLeaderElector(options) }, options)
        val suspend = SuspendLeaderElectorLeaseAdapter({ LocalSuspendLeaderElector(options) }, options)

        try {
            val blockingHandle = blocking.tryAcquire(LeaderSlot("direct-blocking-adapter", "request-node"))
                .shouldNotBeNull()
            val suspendHandle = suspend.tryAcquire(LeaderSlot("direct-suspend-adapter", "request-node"))
                .shouldNotBeNull()

            await.atMost(5.seconds).untilAsserted {
                (globalEvents.count { it.source == LeaderLeaseExtensionSource.WATCHDOG } > 0).shouldBeTrue()
            }
            scopedEvents.size shouldBeEqualTo 0
            blockingHandle.release()
            suspendHandle.release()
        } finally {
            suspend.close()
            global.close()
            scoped.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `blocking adapter preserves caller slot and delegates lifecycle`() {
        val options = LeaderElectionOptions(
            waitTime = 2.seconds,
            leaseTime = 1.seconds,
            nodeId = "backend-node",
        )
        val slot = LeaderSlot("adapter-lock", "request-node")
        val adapter = LeaderElectorLeaseAdapter({ LocalLeaderElector(options) }, options)

        val handle = adapter.tryAcquire(slot).shouldNotBeNull()
        handle.auditLeaderId shouldBeEqualTo "request-node"
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

        val handle = adapter.tryAcquire(slot).shouldNotBeNull()
        handle.auditLeaderId shouldBeEqualTo "request-node"
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

        await.atMost(2.seconds).untilAsserted {
            (rejectedTicks.get() > 0).shouldBeTrue()
        }
        handle.shouldNotBeNull().release()

        (rejectedTicks.get() > 0).shouldBeTrue()
    }

    private suspend fun currentContextHasLockHandle(): Boolean =
        coroutineContext[LockHandleElement] != null
}

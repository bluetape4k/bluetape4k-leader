package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class LocalLeaderLeaseAcquirerTest {

    @Test
    fun `lock-name overload captures configured node and slot overload preserves caller identity`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 1_000.milliseconds, nodeId = "node-a"),
        )

        val byName = elector.tryAcquire("request-lock").shouldNotBeNull()
        byName.auditLeaderId shouldBeEqualTo "node-a"
        byName.lockName shouldBeEqualTo "request-lock"
        byName.release()

        val slot = LeaderSlot("request-lock", "caller-b")
        val bySlot = elector.tryAcquire(slot).shouldNotBeNull()
        bySlot.auditLeaderId shouldBeEqualTo "caller-b"
        bySlot.release()
    }

    @Test
    fun `contention returns null and release is safe from another thread`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 1_000.milliseconds, nodeId = "node-a"),
        )
        val first = elector.tryAcquire("request-lock").shouldNotBeNull()
        val skipped = AtomicReference<io.bluetape4k.leader.LeaderLeaseHandle?>()
        val contenderDone = CountDownLatch(1)
        val contender = Thread {
            skipped.set(elector.tryAcquire("request-lock"))
            contenderDone.countDown()
        }
        contender.start()
        contenderDone.await(1, TimeUnit.SECONDS).shouldBeTrue()

        skipped.get() shouldBeEqualTo null
        val released = CountDownLatch(1)
        val releaser = Thread {
            first.release()
            released.countDown()
        }
        releaser.start()
        released.await(1, TimeUnit.SECONDS).shouldBeTrue()
        releaser.join(1_000)
        first.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
        first.close()
        elector.tryAcquire("request-lock").shouldNotBeNull().release()
    }

    @Test
    fun `extend and ownership are generation fenced`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 60.milliseconds, nodeId = "node-a"),
        )
        val handle = elector.tryAcquire("request-lock").shouldNotBeNull()

        handle.isStillHeld().shouldBeTrue()
        handle.extend(500.milliseconds).isExtended.shouldBeTrue()
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.HELD
        handle.release()
        handle.release()
        handle.isStillHeld().shouldBeFalse()
        handle.extend(100.milliseconds).isExtended.shouldBeFalse()
    }

    @Test
    fun `release preserves the configured minimum lease time`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(
                waitTime = 25.milliseconds,
                leaseTime = 500.milliseconds,
                minLeaseTime = 80.milliseconds,
                nodeId = "node-a",
            ),
        )
        val handle = elector.tryAcquire("request-lock").shouldNotBeNull()
        val started = System.nanoTime()

        handle.release()

        val elapsed = (System.nanoTime() - started).nanoseconds
        (elapsed >= 40.milliseconds).shouldBeTrue()
    }

    @Test
    fun `suspend acquirer uses the same slot identity and release contract`() = runSuspendIO {
        val elector = LocalSuspendLeaderElector(
            LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 1_000.milliseconds, nodeId = "node-a"),
        )
        val handle = elector.tryAcquire(LeaderSlot("suspend-lock", "caller-b")).shouldNotBeNull()

        handle.auditLeaderId shouldBeEqualTo "caller-b"
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.HELD
        handle.isStillHeld().shouldBeTrue()
        handle.release()
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
    }
}

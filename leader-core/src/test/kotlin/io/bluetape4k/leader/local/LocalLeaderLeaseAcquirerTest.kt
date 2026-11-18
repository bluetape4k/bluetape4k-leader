package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class LocalLeaderLeaseAcquirerTest {

    companion object: KLogging()

    @Test
    fun `lock-name overload captures configured node and slot overload preserves caller identity`() {
        val options =
            LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 1_000.milliseconds, nodeId = "node-a")
        val elector = LocalLeaderElector(options)

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
        val options =
            LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 1_000.milliseconds, nodeId = "node-a")
        val elector = LocalLeaderElector(options)

        val first = elector.tryAcquire("request-lock").shouldNotBeNull()
        val skipped = AtomicReference<io.bluetape4k.leader.LeaderLeaseHandle?>()
        val contenderDone = CountDownLatch(1)
        thread {
            skipped.set(elector.tryAcquire("request-lock"))
            contenderDone.countDown()
        }
        contenderDone.await(1.seconds).shouldBeTrue()

        skipped.get().shouldBeNull()
        val released = CountDownLatch(1)
        val releaser = thread {
            first.release()
            released.countDown()
        }
        released.await(1.seconds).shouldBeTrue()
        releaser.join(1_000)
        first.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
        first.close()
        elector.tryAcquire("request-lock").shouldNotBeNull().release()
    }

    @Test
    fun `extend and ownership are generation fenced`() {
        val options = LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 60.milliseconds, nodeId = "node-a")
        val elector = LocalLeaderElector(options)

        val handle = elector.tryAcquire("request-lock").shouldNotBeNull()

        log.debug { "handle=$handle" }
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
        val options = LeaderElectionOptions(
            waitTime = 25.milliseconds,
            leaseTime = 500.milliseconds,
            minLeaseTime = 80.milliseconds,
            nodeId = "node-a",
        )
        val elector = LocalLeaderElector(options)

        val handle = elector.tryAcquire("request-lock").shouldNotBeNull()
        val started = System.nanoTime()

        handle.release()

        val elapsed = (System.nanoTime() - started).nanoseconds
        log.debug { "elapsed=$elapsed" }
        elapsed shouldBeGreaterOrEqualTo 40.milliseconds
    }

    @Test
    fun `suspend acquirer uses the same slot identity and release contract`() = runSuspendIO {
        val options = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 1_000.milliseconds,
            nodeId = "node-a"
        )
        val elector = LocalSuspendLeaderElector(options)
        val slot = LeaderSlot("suspend-lock", "caller-b")

        val handle = elector.tryAcquire(slot).shouldNotBeNull()

        log.debug { "handle=$handle" }
        handle.auditLeaderId shouldBeEqualTo slot.leaderId
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.HELD
        handle.isStillHeld().shouldBeTrue()
        handle.release()
        handle.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
    }
}

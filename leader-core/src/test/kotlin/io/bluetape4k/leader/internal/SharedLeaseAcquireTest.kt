package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.virtualThread
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SharedLeaseAcquireTest {

    companion object: KLogging()

    @Test
    fun `same slot shares one backend acquire and releases physical handle after last waiter`() {
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1)
        val backendCalls = AtomicInteger()
        val releases = AtomicInteger()
        val backendStarted = CountDownLatch(1)
        val allowBackend = CountDownLatch(1)
        val shared = SharedLeaseAcquire(
            scheduler = scheduler,
            acquire = {
                backendCalls.incrementAndGet()
                backendStarted.countDown()
                allowBackend.await(1.seconds)
                TestHandle(releases)
            },
        )
        val slot = LeaderSlot("shared-lock", "node")

        val firstFuture = CompletableFuture<LeaderLeaseHandle?>()
        virtualThread {
            firstFuture.complete(shared.tryAcquire(slot, 1.seconds))
        }
        backendStarted.await(1.seconds)

        val secondFuture = CompletableFuture<LeaderLeaseHandle?>()
        virtualThread {
            secondFuture.complete(shared.tryAcquire(slot, 1.seconds))
        }

        allowBackend.countDown()
        val first = firstFuture.get(1.seconds)
        val second = secondFuture.get(1.seconds)

        backendCalls.get() shouldBeEqualTo 1
        first.shouldNotBeNull()

        if (second != null) {
            shared.activeAttempts shouldBeEqualTo 1
            first.release()
            releases.get() shouldBeEqualTo 0
            second.release()
        } else {
            first.release()
        }
        releases.get() shouldBeEqualTo 1
        shared.activeAttempts shouldBeEqualTo 0

        shared.close()
        scheduler.close()
    }

    @Test
    fun `published attempt rejects later contention without a second backend acquire`() {
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1)
        val backendCalls = AtomicInteger()
        val releases = AtomicInteger()

        val shared = SharedLeaseAcquire(
            scheduler = scheduler,
            acquire = {
                backendCalls.incrementAndGet()
                TestHandle(releases)
            },
        )

        val slot = LeaderSlot("published-lock", "node")

        val first = shared.tryAcquire(slot, 1.seconds).shouldNotBeNull()
        shared.tryAcquire(slot, 20.milliseconds).shouldBeNull()

        backendCalls.get() shouldBeEqualTo 1
        shared.activeAttempts shouldBeEqualTo 1
        first.release()
        releases.get() shouldBeEqualTo 1
        shared.activeAttempts shouldBeEqualTo 0

        shared.close()
        scheduler.close()
    }

    @Test
    fun `scheduler rejection returns contention without retaining an attempt`() {
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1)
        scheduler.close()
        val shared = SharedLeaseAcquire(
            scheduler = scheduler,
            acquire = { TestHandle(AtomicInteger()) },
        )

        val slot = LeaderSlot("rejected-lock", "node")

        shared.tryAcquire(slot, 1.seconds).shouldBeNull()
        shared.activeAttempts shouldBeEqualTo 0
    }

    @Test
    fun `close wakes a pending waiter and removes the shared attempt`() {
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1)
        val backendStarted = CountDownLatch(1)
        val allowBackend = CountDownLatch(1)
        val shared = SharedLeaseAcquire(
            scheduler = scheduler,
            acquire = {
                backendStarted.countDown()
                try {
                    allowBackend.await(1.seconds)
                } catch (_: InterruptedException) {
                    allowBackend.await(1.seconds)
                }
                TestHandle(AtomicInteger())
            },
        )
        val future = CompletableFuture<LeaderLeaseHandle?>()
        val slot = LeaderSlot("close-lock", "node")
        virtualThread {
            future.complete(shared.tryAcquire(slot, 5.seconds))
        }

        backendStarted.await(1.seconds)
        shared.close()

        future.get(1.seconds).shouldBeNull()
        shared.activeAttempts shouldBeEqualTo 0
        allowBackend.countDown()
        scheduler.awaitIdle(1.seconds).shouldBeTrue()
        scheduler.close()
    }

    @Test
    fun `timed out queued attempt terminalizes reservation and cancels backend task`() {
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)

        scheduler.submit {
            blockerStarted.countDown()
            releaseBlocker.await(1.seconds)
        }
        blockerStarted.await(1.seconds).shouldBeTrue()

        val backendCalls = AtomicInteger()
        val reservationsClosed = AtomicInteger()
        val shared = SharedLeaseAcquire(
            scheduler = scheduler,
            acquire = {
                backendCalls.incrementAndGet()
                TestHandle(AtomicInteger())
            },
            reserveAttempt = {
                AutoCloseable { reservationsClosed.incrementAndGet() }
            },
        )
        val slot = LeaderSlot("queued-timeout", "node")
        shared.tryAcquire(slot, 20.milliseconds).shouldBeNull()
        shared.activeAttempts shouldBeEqualTo 0
        reservationsClosed.get() shouldBeEqualTo 1

        releaseBlocker.countDown()
        scheduler.awaitIdle(1.seconds).shouldBeEqualTo(true)
        backendCalls.get() shouldBeEqualTo 0

        shared.close()
        scheduler.close()
    }

    private class TestHandle(
        private val releases: AtomicInteger,
    ): LeaderLeaseHandle {
        override val lockName: String = "shared-lock"
        override val auditLeaderId: String = "node"
        override val acquiredAt: Instant = Instant.now()

        override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Rejected

        override fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD

        override fun isStillHeld(): Boolean = true

        override fun release() {
            releases.incrementAndGet()
        }
    }
}

package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderSlot
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class LeaderLeaseLifecycleTest {

    @Test
    fun `contention is null and release is fenced and at most once`() {
        val releases = AtomicInteger()
        val callbacks = FakeCallbacks(releases)
        val lifecycle = LeaderLeaseLifecycle(LeaderElectionOptions(leaseTime = 1.seconds), callbacks)
        val slot = LeaderSlot("lifecycle-lock", "node-a")

        val first = lifecycle.tryAcquire(slot).shouldNotBeNull()
        lifecycle.tryAcquire(slot).let { it == null }.shouldBeTrue()
        first.extend(1.seconds).isExtended.shouldBeTrue()
        first.release()
        first.release()
        releases.get() shouldBeEqualTo 1
        first.ownershipStatus() shouldBeEqualTo LeaseOwnershipStatus.NOT_HELD
        first.isStillHeld().shouldBeFalse()
    }

    @Test
    fun `suspend release rethrows backend cancellation`() = runSuspendIO {
        val cancellation = CancellationException("backend release cancelled")
        val lifecycle = SuspendLeaderLeaseLifecycle(
            options = LeaderElectionOptions(leaseTime = 1.seconds),
            callbacks = SuspendCancellationCallbacks(cancellation),
        )
        val handle = lifecycle.tryAcquire(LeaderSlot("suspend-lifecycle-lock", "node")).shouldNotBeNull()

        val thrown = assertFailsWith<CancellationException> { handle.release() }
        thrown.message shouldBeEqualTo cancellation.message
    }

    private class FakeCallbacks(private val releases: AtomicInteger) : LeaseBackendCallbacks {
        private var held = false

        override fun acquire(slot: LeaderSlot, waitDeadlineNanos: Long, transportDeadlineNanos: Long): BackendLease? {
            if (held) return null
            held = true
            return BackendLease(slot, Instant.now(), System.nanoTime())
        }

        override fun extend(lease: BackendLease, duration: kotlin.time.Duration, deadlineNanos: Long): ExtendOutcome =
            if (held) ExtendOutcome.Extended(Instant.now().plusSeconds(1)) else ExtendOutcome.NotHeld

        override fun release(lease: BackendLease, deadlineNanos: Long): BackendReleaseOutcome {
            releases.incrementAndGet()
            if (!held) return BackendReleaseOutcome.NOT_HELD
            held = false
            return BackendReleaseOutcome.RELEASED
        }

        override fun isHeld(lease: BackendLease, deadlineNanos: Long): LeaseOwnershipStatus =
            if (held) LeaseOwnershipStatus.HELD else LeaseOwnershipStatus.NOT_HELD
    }

    private class SuspendCancellationCallbacks(
        private val cancellation: CancellationException,
    ) : SuspendLeaseBackendCallbacks {
        override suspend fun acquire(
            slot: LeaderSlot,
            waitDeadlineNanos: Long,
            transportDeadlineNanos: Long,
        ): BackendLease = BackendLease(slot, Instant.now(), System.nanoTime())

        override suspend fun extend(
            lease: BackendLease,
            duration: kotlin.time.Duration,
            deadlineNanos: Long,
        ): ExtendOutcome = ExtendOutcome.Extended(Instant.now().plusSeconds(1))

        override suspend fun release(lease: BackendLease, deadlineNanos: Long): BackendReleaseOutcome {
            throw cancellation
        }

        override suspend fun isHeld(lease: BackendLease, deadlineNanos: Long): LeaseOwnershipStatus =
            LeaseOwnershipStatus.HELD
    }
}

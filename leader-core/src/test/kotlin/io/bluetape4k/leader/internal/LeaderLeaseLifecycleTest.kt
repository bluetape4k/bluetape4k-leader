package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

class LeaderLeaseLifecycleTest {

    companion object: KLoggingChannel()

    @Test
    fun `contention is null and release is fenced and at most once`() {
        val releases = AtomicInteger()
        val callbacks = FakeCallbacks(releases)
        val lifecycle = LeaderLeaseLifecycle(LeaderElectionOptions(leaseTime = 1.seconds), callbacks)
        val slot = LeaderSlot("lifecycle-lock", "node-a")

        val first = lifecycle.tryAcquire(slot).shouldNotBeNull()
        lifecycle.tryAcquire(slot).shouldBeNull()
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
        val slot = LeaderSlot("suspend-lifecycle-lock", "node")
        val handle = lifecycle.tryAcquire(slot).shouldNotBeNull()

        val thrown = assertFailsWith<CancellationException> {
            handle.release()
        }
        thrown.message shouldBeEqualTo cancellation.message
    }

    @Test
    fun `suspend release waits for positive submillisecond minimum lease remainder`() = runTest {
        var monotonicNowNanos = 0L
        val releases = AtomicInteger()
        val callbacks = SuspendImmediateCallbacks({ monotonicNowNanos }, releases)
        val lifecycle = SuspendLeaderLeaseLifecycle(
            options = LeaderElectionOptions(leaseTime = 1.seconds, minLeaseTime = 500.microseconds),
            callbacks = callbacks,
            monotonicNanos = { monotonicNowNanos },
        )
        val handle = lifecycle.tryAcquire(LeaderSlot("submillisecond-lock", "node")).shouldNotBeNull()

        monotonicNowNanos = 1L
        handle.release()

        testScheduler.currentTime shouldBeEqualTo 1L
        releases.get() shouldBeEqualTo 1
    }

    @Test
    fun `suspend release completes minimum lease cleanup after caller cancellation`() = runTest {
        var monotonicNowNanos = 0L
        val releases = AtomicInteger()
        val callbacks = SuspendImmediateCallbacks({ monotonicNowNanos }, releases)
        val lifecycle = SuspendLeaderLeaseLifecycle(
            options = LeaderElectionOptions(leaseTime = 1.seconds, minLeaseTime = 500.microseconds),
            callbacks = callbacks,
            monotonicNanos = { monotonicNowNanos },
        )
        val handle = lifecycle.tryAcquire(LeaderSlot("cancelled-release-lock", "node")).shouldNotBeNull()

        monotonicNowNanos = 1L
        val releaseJob = launch { handle.release() }
        runCurrent()
        releaseJob.isActive.shouldBeTrue()

        releaseJob.cancel()
        advanceUntilIdle()

        releaseJob.isCancelled.shouldBeTrue()
        testScheduler.currentTime shouldBeEqualTo 1L
        releases.get() shouldBeEqualTo 1
    }

    private class SuspendImmediateCallbacks(
        private val acquiredAtNanos: () -> Long,
        private val releases: AtomicInteger,
    ): SuspendLeaseBackendCallbacks {
        override suspend fun acquire(
            slot: LeaderSlot,
            waitDeadlineNanos: Long,
            transportDeadlineNanos: Long,
        ): BackendLease = BackendLease(slot, Instant.now(), acquiredAtNanos())

        override suspend fun extend(
            lease: BackendLease,
            duration: kotlin.time.Duration,
            deadlineNanos: Long,
        ): ExtendOutcome = ExtendOutcome.Extended(Instant.now().plusSeconds(1))

        override suspend fun release(lease: BackendLease, deadlineNanos: Long): BackendReleaseOutcome {
            releases.incrementAndGet()
            return BackendReleaseOutcome.RELEASED
        }

        override suspend fun isHeld(lease: BackendLease, deadlineNanos: Long): LeaseOwnershipStatus =
            LeaseOwnershipStatus.HELD
    }

    private class FakeCallbacks(private val releases: AtomicInteger): LeaseBackendCallbacks {
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
    ): SuspendLeaseBackendCallbacks {
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

package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.logging.KLogging
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderManagementActionRegistryTest {

    companion object: KLogging()

    @Test
    fun `held handle is released exactly once and emits sanitized terminal observation`() {
        val observations = mutableListOf<LeaderManagementActionObservation>()
        val handle = FakeHandle("primary") {
            listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD)
        }
        val registry = LeaderManagementActionRegistry(observer = { observations += it })

        registry.register(handle).accepted.shouldBeTrue()
        registry.release("primary") shouldBeEqualTo LeaderManagementActionResult(
            LeaderManagementAction.RELEASE,
            LeaderManagementActionOutcome.RELEASED,
            mutationAttempted = true,
        )
        handle.releaseCalls.get() shouldBeEqualTo 1
        handle.ownershipCalls.get() shouldBeEqualTo 2
        observations.size shouldBeEqualTo 1
        observations.single().phase shouldBeEqualTo LeaderManagementActionPhase.TERMINALIZED
        observations.single().quarantined.shouldBeFalse()
        registry.close()
    }

    @Test
    fun `adapter surface is propagated to terminal observation`() {
        val observations = mutableListOf<LeaderManagementActionObservation>()
        val handle = FakeHandle("spring-surface") {
            listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD)
        }
        val registry = LeaderManagementActionRegistry(
            observer = { observations += it },
        )
        registry.register(handle)

        registry.release("spring-surface", LeaderManagementActionSurface.SPRING).outcome shouldBeEqualTo
                LeaderManagementActionOutcome.RELEASED
        observations.single().surface shouldBeEqualTo LeaderManagementActionSurface.SPRING
        registry.close()
    }

    @Test
    fun `not held and unknown ownership never call release`() {
        val notHeld = FakeHandle("not-held") { listOf(LeaseOwnershipStatus.NOT_HELD) }
        val unknown = FakeHandle("unknown") { listOf(LeaseOwnershipStatus.UNKNOWN) }
        val registry = LeaderManagementActionRegistry()
        registry.register(notHeld)
        registry.register(unknown)

        registry.release("not-held").outcome shouldBeEqualTo LeaderManagementActionOutcome.NOT_HELD
        registry.release("unknown").outcome shouldBeEqualTo LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN
        notHeld.releaseCalls.get() shouldBeEqualTo 0
        unknown.releaseCalls.get() shouldBeEqualTo 0
        registry.close()
    }

    @Test
    fun `invalid unregistered and ambiguous selectors are typed without backend calls`() {
        val first = FakeHandle("same") { listOf(LeaseOwnershipStatus.HELD) }
        val second = FakeHandle("same") { listOf(LeaseOwnershipStatus.HELD) }
        val registry = LeaderManagementActionRegistry()
        registry.register(first)
        registry.register(second)

        registry.release("bad/name").outcome shouldBeEqualTo LeaderManagementActionOutcome.INVALID_LOCK_NAME
        registry.release("missing").outcome shouldBeEqualTo LeaderManagementActionOutcome.NOT_REGISTERED
        registry.release("same").outcome shouldBeEqualTo LeaderManagementActionOutcome.AMBIGUOUS
        first.ownershipCalls.get() shouldBeEqualTo 0
        second.ownershipCalls.get() shouldBeEqualTo 0
        registry.close()
    }

    @Test
    fun `registration cap counts repeated identity tokens and closes by reference`() {
        val handle = FakeHandle("cap") { listOf(LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(maxRegistrations = 2)
        val first = registry.register(handle)
        val second = registry.register(handle)
        val rejected = registry.register(FakeHandle("other") { listOf(LeaseOwnershipStatus.NOT_HELD) })

        first.outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.ACCEPTED
        second.outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.ACCEPTED
        rejected.outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.CAPACITY_REJECTED

        registry.registeredLockNames() shouldBeEqualTo listOf("cap")
        rejected.close()
        first.close()

        registry.registeredLockNames() shouldBeEqualTo listOf("cap")
        second.close()

        registry.registeredLockNames().shouldBeEmpty()
        registry.close()
    }

    @Test
    fun `same lock action is admitted once while another lock remains independent`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val slow = FakeHandle("slow", ownership = {
            entered.countDown()
            unblock.await(1.seconds)
            listOf(LeaseOwnershipStatus.NOT_HELD)
        })
        val fast = FakeHandle("fast") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(maxInFlightActions = 2, actionQueueCapacity = 1)

        registry.register(slow)
        registry.register(fast)

        val first = thread(start = true) { registry.release("slow") }

        entered.await(1.seconds).shouldBeTrue()
        registry.release("slow").outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_IN_PROGRESS
        registry.release("fast").outcome shouldBeEqualTo LeaderManagementActionOutcome.RELEASED

        unblock.countDown()
        first.join(2_000)
        registry.close()
    }

    @Test
    fun `runtime release and post check failures are sanitized`() {
        val releaseFailure = FakeHandle(
            "release-failure",
            ownership = { listOf(LeaseOwnershipStatus.HELD) },
            onRelease = { error("backend release failure") }
        )
        val postCheckFailure = FakeHandle(
            "post-failure",
            ownership = { listOf(LeaseOwnershipStatus.HELD) },
            onRelease = {},
            postCheck = { throw IllegalStateException("post failure") }
        )

        val registry = LeaderManagementActionRegistry()
        registry.register(releaseFailure)
        registry.register(postCheckFailure)

        registry.release("release-failure") shouldBeEqualTo LeaderManagementActionResult(
            LeaderManagementAction.RELEASE,
            LeaderManagementActionOutcome.RELEASE_FAILED,
            mutationAttempted = true,
        )
        registry.release("post-failure") shouldBeEqualTo LeaderManagementActionResult(
            LeaderManagementAction.RELEASE,
            LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
            mutationAttempted = true,
        )
        registry.close()
    }

    @Test
    fun `error is rethrown and reservation is cleaned after callback`() {
        val handle = FakeHandle("fatal") { listOf(LeaseOwnershipStatus.HELD) }
        handle.onRelease = { throw AssertionError("fatal callback") }
        val registry = LeaderManagementActionRegistry()
        registry.register(handle)

        var errorRethrown = false
        try {
            registry.release("fatal")
        } catch (_: AssertionError) {
            errorRethrown = true
        }
        errorRethrown.shouldBeTrue()
        registry.quarantinedCount() shouldBeEqualTo 0
        registry.close()
    }

    @Test
    fun `timeout before release returns without mutation and eventually frees reservation`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val handle = FakeHandle(
            lockName = "timeout-before",
            ownership = {
                entered.countDown()
                while (true) {
                    try {
                        unblock.await(5.seconds)
                        break
                    } catch (_: InterruptedException) {
                        // emulate a slow pre-check that honours neither cancellation nor retry.
                    }
                }
                listOf(LeaseOwnershipStatus.HELD)
            }
        )
        val registry = LeaderManagementActionRegistry(
            actionTimeout = 40.milliseconds,
            cleanupGrace = 100.milliseconds,
        )
        registry.register(handle)
        val result = registry.release("timeout-before")
        result.outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_TIMED_OUT
        result.mutationAttempted.shouldBeFalse()
        entered.await(1.seconds).shouldBeTrue()
        unblock.countDown()

        await atMost 2.seconds withPollInterval 25.milliseconds until {
            registry.quarantinedCount() == 0
        }

        handle.releaseCalls.get() shouldBeEqualTo 0
        registry.close()
    }

    @Test
    fun `timeout after release keeps mutation flag and quarantine until worker exits`() {
        val releaseEntered = CountDownLatch(1)
        val releaseDone = CountDownLatch(1)
        val handle = FakeHandle("timeout-after") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        handle.onRelease = {
            releaseEntered.countDown()
            while (releaseDone.count == 1L) {
                try {
                    releaseDone.await(5.seconds)
                } catch (_: InterruptedException) {
                    // non-interruptible callback for quarantine coverage.
                }
            }
        }
        val registry = LeaderManagementActionRegistry(
            actionTimeout = 40.milliseconds,
            cleanupGrace = 20.milliseconds,
        )
        registry.register(handle)
        val result = registry.release("timeout-after")
        releaseEntered.await(1.seconds).shouldBeTrue()
        result.outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_TIMED_OUT
        result.mutationAttempted.shouldBeTrue()

        await atMost 2.seconds until {
            registry.quarantinedCount() > 0
        }

        // eventually { registry.quarantinedCount().shouldBeGreaterThan(0) }
        releaseDone.countDown()

        await atMost 2.seconds until {
            registry.quarantinedCount() == 0
        }
        // eventually { registry.quarantinedCount() shouldBeEqualTo 0 }
        registry.close()
    }

    @Test
    fun `observer failure does not change result or cleanup`() {
        val handle = FakeHandle("observer") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(
            observer = { throw AssertionError("observer") },
        )
        registry.register(handle)

        registry.release("observer").outcome shouldBeEqualTo LeaderManagementActionOutcome.RELEASED
        registry.quarantinedCount() shouldBeEqualTo 0
        registry.close()
    }

    @Test
    fun `close quiesces admission and does not release registered lease`() {
        val handle = FakeHandle("close") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(closeTimeout = 1.seconds)
        registry.register(handle)

        registry.closeAndDrain().shouldBeTrue()
        registry.release("close").outcome shouldBeEqualTo LeaderManagementActionOutcome.REGISTRY_CLOSED
        handle.releaseCalls.get() shouldBeEqualTo 0
    }

    private class FakeHandle(
        override val lockName: String,
        var onRelease: () -> Unit = {},
        private val postCheck: (() -> Unit)? = null,
        private val ownership: () -> List<LeaseOwnershipStatus>,
    ): LeaderLeaseHandle {
        val ownershipCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        override val auditLeaderId: String = "test-leader"
        override val acquiredAt: Instant = Instant.EPOCH

        override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.NotHeld

        override fun ownershipStatus(): LeaseOwnershipStatus {
            ownershipCalls.incrementAndGet()
            if (ownershipCalls.get() > 1) postCheck?.invoke()
            val statuses = ownership()
            return statuses.getOrNull(ownershipCalls.get() - 1)
                ?: statuses.lastOrNull()
                ?: LeaseOwnershipStatus.UNKNOWN
        }

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() {
            releaseCalls.incrementAndGet()
            onRelease()
        }
    }
}

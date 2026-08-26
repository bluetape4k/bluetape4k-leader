package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeGreaterThan
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class LeaderManagementActionRegistryTest {

    @Test
    fun `held handle is released exactly once and emits sanitized terminal observation`() {
        val observations = mutableListOf<LeaderManagementActionObservation>()
        val handle = FakeHandle("primary") {
            listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD)
        }
        val registry = LeaderManagementActionRegistry(observer = LeaderManagementActionObserver { observations += it })

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
            observer = LeaderManagementActionObserver { observations += it },
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
        registry.registeredLockNames().isEmpty().shouldBeTrue()
        registry.close()
    }

    @Test
    fun `same lock action is admitted once while another lock remains independent`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val slow = FakeHandle("slow", ownership = {
            entered.countDown()
            unblock.await(1, TimeUnit.SECONDS)
            listOf(LeaseOwnershipStatus.NOT_HELD)
        })
        val fast = FakeHandle("fast") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(maxInFlightActions = 2, actionQueueCapacity = 1)
        registry.register(slow)
        registry.register(fast)

        val first = thread(start = true) { registry.release("slow") }
        entered.await(1, TimeUnit.SECONDS).shouldBeTrue()
        registry.release("slow").outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_IN_PROGRESS
        registry.release("fast").outcome shouldBeEqualTo LeaderManagementActionOutcome.RELEASED
        unblock.countDown()
        first.join(2_000)
        registry.close()
    }

    @Test
    fun `runtime release and post check failures are sanitized`() {
        val releaseFailure = FakeHandle("release-failure", ownership = {
            listOf(LeaseOwnershipStatus.HELD)
        }, onRelease = { error("backend release failure") })
        val postCheckFailure = FakeHandle("post-failure", ownership = {
            listOf(LeaseOwnershipStatus.HELD)
        }, onRelease = {}, postCheck = { throw IllegalStateException("post failure") })
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
        val handle = FakeHandle("timeout-before", ownership = {
            entered.countDown()
            while (true) {
                try {
                    unblock.await(5, TimeUnit.SECONDS)
                    break
                } catch (_: InterruptedException) {
                    // emulate a slow pre-check that honours neither cancellation nor retry.
                }
            }
            listOf(LeaseOwnershipStatus.HELD)
        })
        val registry = LeaderManagementActionRegistry(
            actionTimeout = 40.milliseconds,
            cleanupGrace = 100.milliseconds,
        )
        registry.register(handle)
        val result = registry.release("timeout-before")
        result.outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_TIMED_OUT
        result.mutationAttempted.shouldBeFalse()
        entered.await(1, TimeUnit.SECONDS).shouldBeTrue()
        unblock.countDown()
        eventually { registry.quarantinedCount() shouldBeEqualTo 0 }
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
                    releaseDone.await(5, TimeUnit.SECONDS)
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
        releaseEntered.await(1, TimeUnit.SECONDS).shouldBeTrue()
        result.outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_TIMED_OUT
        result.mutationAttempted.shouldBeTrue()
        eventually { registry.quarantinedCount().shouldBeGreaterThan(0) }
        releaseDone.countDown()
        eventually { registry.quarantinedCount() shouldBeEqualTo 0 }
        registry.close()
    }

    @Test
    fun `observer failure does not change result or cleanup`() {
        val handle = FakeHandle("observer") { listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD) }
        val registry = LeaderManagementActionRegistry(
            observer = LeaderManagementActionObserver { throw AssertionError("observer") },
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
    ) : LeaderLeaseHandle {
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

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 2.seconds.inWholeNanoseconds
        var last: AssertionError? = null
        while (System.nanoTime() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                last = error
                Thread.sleep(5)
            }
        }
        throw last ?: AssertionError("condition did not become true")
    }
}

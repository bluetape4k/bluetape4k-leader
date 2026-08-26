package io.bluetape4k.leader.coroutines

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderManagementAction
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionResult
import io.bluetape4k.leader.LeaderManagementRegistrationOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class SuspendLeaderManagementActionRegistryTest {

    @Test
    fun `held suspend handle is released exactly once`() = runSuspendIO {
        val observations = mutableListOf<io.bluetape4k.leader.LeaderManagementActionObservation>()
        val handle = FakeSuspendHandle(
            lockName = "suspend-primary",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
        )
        val registry = SuspendLeaderManagementActionRegistry(
            observer = io.bluetape4k.leader.LeaderManagementActionObserver { observations += it },
        )
        registry.register(handle).accepted.shouldBeTrue()

        registry.release("suspend-primary") shouldBeEqualTo LeaderManagementActionResult(
            LeaderManagementAction.RELEASE,
            LeaderManagementActionOutcome.RELEASED,
            mutationAttempted = true,
        )
        handle.releaseCalls.get() shouldBeEqualTo 1
        handle.ownershipCalls.get() shouldBeEqualTo 2
        observations.size shouldBeEqualTo 1
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `adapter surface is propagated to terminal observation`() = runSuspendIO {
        val observations = mutableListOf<io.bluetape4k.leader.LeaderManagementActionObservation>()
        val handle = FakeSuspendHandle(
            lockName = "ktor-surface",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
        )
        val registry = SuspendLeaderManagementActionRegistry(
            observer = io.bluetape4k.leader.LeaderManagementActionObserver { observations += it },
        )
        registry.register(handle)

        registry.release(
            "ktor-surface",
            io.bluetape4k.leader.LeaderManagementActionSurface.KTOR,
        ).outcome shouldBeEqualTo LeaderManagementActionOutcome.RELEASED
        observations.single().surface shouldBeEqualTo io.bluetape4k.leader.LeaderManagementActionSurface.KTOR
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `registration and selector outcomes match blocking registry`() = runSuspendIO {
        val first = FakeSuspendHandle("same", listOf(LeaseOwnershipStatus.HELD))
        val second = FakeSuspendHandle("same", listOf(LeaseOwnershipStatus.HELD))
        val registry = SuspendLeaderManagementActionRegistry(maxRegistrations = 2)
        registry.register(first).outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.ACCEPTED
        registry.register(second).outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.ACCEPTED

        registry.release("bad/name").outcome shouldBeEqualTo LeaderManagementActionOutcome.INVALID_LOCK_NAME
        registry.release("missing").outcome shouldBeEqualTo LeaderManagementActionOutcome.NOT_REGISTERED
        registry.release("same").outcome shouldBeEqualTo LeaderManagementActionOutcome.AMBIGUOUS
        first.ownershipCalls.get() shouldBeEqualTo 0
        second.ownershipCalls.get() shouldBeEqualTo 0
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `caller cancellation before precheck never mutates the lease`() = runSuspendIO {
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val handle = FakeSuspendHandle(
            lockName = "cancel-before",
            statuses = listOf(LeaseOwnershipStatus.HELD),
            onOwnership = {
                entered.complete(Unit)
                unblock.await()
            },
        )
        val registry = SuspendLeaderManagementActionRegistry(cleanupGrace = 100.milliseconds)
        registry.register(handle)
        val request = async { registry.release("cancel-before") }
        entered.await()
        request.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { request.await() }
        handle.releaseCalls.get() shouldBeEqualTo 0
        unblock.complete(Unit)
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `timeout after release keeps mutation and quarantine until noncancellable callback exits`() = runSuspendIO {
        val releaseEntered = CompletableDeferred<Unit>()
        val releaseDone = CompletableDeferred<Unit>()
        val handle = FakeSuspendHandle(
            lockName = "cancel-after",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
            onRelease = {
                releaseEntered.complete(Unit)
                releaseDone.await()
            },
        )
        val registry = SuspendLeaderManagementActionRegistry(
            actionTimeout = 40.milliseconds,
            cleanupGrace = 20.milliseconds,
        )
        registry.register(handle)
        val result = registry.release("cancel-after")
        releaseEntered.await()
        result.outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_TIMED_OUT
        result.mutationAttempted.shouldBeTrue()
        eventually { registry.quarantinedCount().shouldBeGreaterThan(0) }
        releaseDone.complete(Unit)
        eventually { registry.quarantinedCount() shouldBeEqualTo 0 }
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `caller cancellation after release is rethrown while registry worker completes`() = runSuspendIO {
        val releaseEntered = CompletableDeferred<Unit>()
        val releaseDone = CompletableDeferred<Unit>()
        val handle = FakeSuspendHandle(
            lockName = "caller-cancel",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
            onRelease = {
                releaseEntered.complete(Unit)
                releaseDone.await()
            },
        )
        val registry = SuspendLeaderManagementActionRegistry(cleanupGrace = 100.milliseconds)
        registry.register(handle)
        val request = async { registry.release("caller-cancel") }
        releaseEntered.await()
        request.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { request.await() }
        releaseDone.complete(Unit)
        eventually { registry.quarantinedCount() shouldBeEqualTo 0 }
        handle.releaseCalls.get() shouldBeEqualTo 1
        registry.closeAndDrain().shouldBeTrue()
    }

    @Test
    fun `worker cap rejects without waiting and close is fail closed`() = runSuspendIO {
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val first = FakeSuspendHandle(
            lockName = "one",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
            onOwnership = {
                entered.complete(Unit)
                unblock.await()
            },
        )
        val second = FakeSuspendHandle("two", listOf(LeaseOwnershipStatus.NOT_HELD))
        val registry = SuspendLeaderManagementActionRegistry(maxInFlightActions = 1)
        registry.register(first)
        registry.register(second)
        val running = launch { registry.release("one") }
        entered.await()
        registry.release("two").outcome shouldBeEqualTo LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED
        unblock.complete(Unit)
        running.join()
        registry.closeAndDrain().shouldBeTrue()
        registry.release("one").outcome shouldBeEqualTo LeaderManagementActionOutcome.REGISTRY_CLOSED
    }

    @Test
    fun `observer exception is isolated and no quarantine remains after success`() = runSuspendIO {
        val handle = FakeSuspendHandle(
            lockName = "observer",
            statuses = listOf(LeaseOwnershipStatus.HELD, LeaseOwnershipStatus.NOT_HELD),
        )
        val registry = SuspendLeaderManagementActionRegistry(
            observer = io.bluetape4k.leader.LeaderManagementActionObserver { throw AssertionError("observer") },
        )
        registry.register(handle)
        registry.release("observer").outcome shouldBeEqualTo LeaderManagementActionOutcome.RELEASED
        registry.quarantinedCount() shouldBeEqualTo 0
        registry.closeAndDrain().shouldBeTrue()
    }

    private class FakeSuspendHandle(
        override val lockName: String,
        private val statuses: List<LeaseOwnershipStatus>,
        private val onOwnership: suspend () -> Unit = {},
        private val onRelease: suspend () -> Unit = {},
    ) : SuspendLeaderLeaseHandle {
        val ownershipCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        override val auditLeaderId: String = "suspend-test"
        override val acquiredAt: Instant = Instant.EPOCH

        override suspend fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.NotHeld

        override suspend fun ownershipStatus(): LeaseOwnershipStatus {
            val call = ownershipCalls.incrementAndGet()
            onOwnership()
            return statuses.getOrNull(call - 1) ?: statuses.lastOrNull() ?: LeaseOwnershipStatus.UNKNOWN
        }

        override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override suspend fun release() {
            releaseCalls.incrementAndGet()
            onRelease()
        }
    }

    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(2.seconds) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(5.milliseconds)
                }
            }
        }
    }
}

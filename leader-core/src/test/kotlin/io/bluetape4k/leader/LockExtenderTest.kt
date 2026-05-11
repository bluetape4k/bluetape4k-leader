package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockExtenderTest {

    // --- helpers ---

    private fun identity(name: String = "test-lock") = LockIdentity(
        lockName = name,
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "testFactory",
    )

    /**
     * Configurable fake delegate — lets tests inject specific [ExtendOutcome] responses.
     */
    private class FakeDelegate(
        private val outcome: ExtendOutcome = ExtendOutcome.Extended(Instant.now()),
        private val heldValue: Boolean = true,
    ) : ExtendDelegate {
        private val _deadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = _deadline

        var extendCalledWith: Duration? = null

        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            extendCalledWith = lockAtMostFor
            return outcome
        }

        override fun isHeld(): Boolean = heldValue
    }

    private fun realHandle(
        name: String = "test-lock",
        delegate: FakeDelegate = FakeDelegate(),
    ): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(name),
            token = "tok-abc",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = delegate,
        )

    private fun failOpenHandle(name: String = "test-lock"): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity(name))

    // --- extendActiveLock() — outside scope ---

    @Test
    fun `extendActiveLock returns false outside any scope`() {
        LockExtender.extendActiveLock(30.seconds).shouldBeFalse()
    }

    @Test
    fun `extendActiveLock with lockName returns false outside any scope`() {
        LockExtender.extendActiveLock("some-lock", 30.seconds).shouldBeFalse()
    }

    // --- extendActiveLock() — Real handle ---

    @Test
    fun `extendActiveLock returns true when delegate returns Extended`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(60.seconds).shouldBeTrue()
        }
    }

    @Test
    fun `extendActiveLock calls delegate extend with correct duration`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(45.seconds)
        }
        assert(delegate.extendCalledWith == 45.seconds) {
            "Expected delegate.extend called with 45s, got ${delegate.extendCalledWith}"
        }
    }

    @Test
    fun `extendActiveLock returns false when delegate returns NotHeld`() {
        val delegate = FakeDelegate(ExtendOutcome.NotHeld)
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(30.seconds).shouldBeFalse()
        }
    }

    @Test
    fun `extendActiveLock returns false when delegate returns WrongThread`() {
        val delegate = FakeDelegate(ExtendOutcome.WrongThread)
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(30.seconds).shouldBeFalse()
        }
    }

    @Test
    fun `extendActiveLock returns false when delegate returns BackendError`() {
        val delegate = FakeDelegate(ExtendOutcome.BackendError(RuntimeException("transient error")))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(30.seconds).shouldBeFalse()
        }
    }

    // --- extendActiveLock() — FailOpen sentinel ---

    @Test
    fun `extendActiveLock returns false inside FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle()) {
            LockExtender.extendActiveLock(30.seconds).shouldBeFalse()
        }
    }

    // --- extendActiveLock(lockName) — mismatched name ---

    @Test
    fun `extendActiveLock with mismatched lockName returns false`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle("lock-a", delegate)) {
            LockExtender.extendActiveLock("lock-b", 30.seconds).shouldBeFalse()
        }
    }

    @Test
    fun `extendActiveLock with matching lockName returns true`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle("lock-a", delegate)) {
            LockExtender.extendActiveLock("lock-a", 30.seconds).shouldBeTrue()
        }
    }

    // --- java.time.Duration overloads ---

    @Test
    fun `extendActiveLock with java Duration returns true for Real handle`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(60.seconds.toJavaDuration()).shouldBeTrue()
        }
    }

    @Test
    fun `extendActiveLock with lockName and java Duration returns true for Real handle`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        LockStateHolder.withPushed(realHandle("my-lock", delegate)) {
            LockExtender.extendActiveLock("my-lock", 60.seconds.toJavaDuration()).shouldBeTrue()
        }
    }

    // --- extendActiveLockDetailed() — sealed ExtendOutcome branches ---

    @Test
    fun `extendActiveLockDetailed returns Extended for successful backend extend`() {
        val expireAt = Instant.now().plusSeconds(60)
        val delegate = FakeDelegate(ExtendOutcome.Extended(expireAt))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            val outcome = LockExtender.extendActiveLockDetailed(60.seconds)
            assert(outcome is ExtendOutcome.Extended) { "Expected Extended, got $outcome" }
        }
    }

    @Test
    fun `extendActiveLockDetailed returns NotHeld when outside scope`() {
        val outcome = LockExtender.extendActiveLockDetailed(30.seconds)
        assert(outcome is ExtendOutcome.NotHeld) { "Expected NotHeld, got $outcome" }
    }

    @Test
    fun `extendActiveLockDetailed returns NotHeld for FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle()) {
            val outcome = LockExtender.extendActiveLockDetailed(30.seconds)
            assert(outcome is ExtendOutcome.NotHeld) { "Expected NotHeld, got $outcome" }
        }
    }

    @Test
    fun `extendActiveLockDetailed returns WrongThread when delegate returns WrongThread`() {
        val delegate = FakeDelegate(ExtendOutcome.WrongThread)
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            val outcome = LockExtender.extendActiveLockDetailed(30.seconds)
            assert(outcome is ExtendOutcome.WrongThread) { "Expected WrongThread, got $outcome" }
        }
    }

    @Test
    fun `extendActiveLockDetailed returns BackendError when delegate returns BackendError`() {
        val cause = RuntimeException("backend down")
        val delegate = FakeDelegate(ExtendOutcome.BackendError(cause))
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            val outcome = LockExtender.extendActiveLockDetailed(30.seconds)
            assert(outcome is ExtendOutcome.BackendError) { "Expected BackendError, got $outcome" }
            assert((outcome as ExtendOutcome.BackendError).cause === cause)
        }
    }

    // --- R2 mitigation: lastExtendDeadline update ---

    @Test
    fun `extendActiveLock sets lastExtendDeadline to approximately now plus duration (R2)`() {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        val beforeCall = Instant.now()
        LockStateHolder.withPushed(realHandle(delegate = delegate)) {
            LockExtender.extendActiveLock(60.seconds)
        }
        val afterCall = Instant.now()

        val deadline = delegate.lastExtendDeadline.get()
        val expectedMin = beforeCall.plusSeconds(59)   // some tolerance
        val expectedMax = afterCall.plusSeconds(61)    // some tolerance

        assert(!deadline.isBefore(expectedMin)) {
            "lastExtendDeadline=$deadline is before expected minimum=$expectedMin"
        }
        assert(!deadline.isAfter(expectedMax)) {
            "lastExtendDeadline=$deadline is after expected maximum=$expectedMax"
        }
    }

    // --- suspend variants ---

    @Test
    fun `extendActiveLockSuspend returns true inside Real handle in coroutineContext`() = runTest {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        withContext(LockHandleElement(realHandle(delegate = delegate))) {
            LockExtender.extendActiveLockSuspend(30.seconds).shouldBeTrue()
        }
    }

    @Test
    fun `extendActiveLockSuspend returns false outside any scope`() = runTest {
        LockExtender.extendActiveLockSuspend(30.seconds).shouldBeFalse()
    }

    @Test
    fun `extendActiveLockSuspend returns false inside FailOpen scope`() = runTest {
        withContext(LockHandleElement(failOpenHandle())) {
            LockExtender.extendActiveLockSuspend(30.seconds).shouldBeFalse()
        }
    }

    @Test
    fun `extendActiveLockSuspend with lockName returns true when names match`() = runTest {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        withContext(LockHandleElement(realHandle("async-lock", delegate))) {
            LockExtender.extendActiveLockSuspend("async-lock", 30.seconds).shouldBeTrue()
        }
    }

    @Test
    fun `extendActiveLockSuspend with mismatched lockName returns false`() = runTest {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        withContext(LockHandleElement(realHandle("async-lock", delegate))) {
            LockExtender.extendActiveLockSuspend("other-lock", 30.seconds).shouldBeFalse()
        }
    }

    // --- extendActiveLockDetailedSuspend() ---

    @Test
    fun `extendActiveLockDetailedSuspend returns Extended for successful backend extend`() = runTest {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now().plusSeconds(30)))
        withContext(LockHandleElement(realHandle(delegate = delegate))) {
            val outcome = LockExtender.extendActiveLockDetailedSuspend(30.seconds)
            assert(outcome is ExtendOutcome.Extended) { "Expected Extended, got $outcome" }
        }
    }

    @Test
    fun `extendActiveLockDetailedSuspend returns NotHeld when outside scope`() = runTest {
        val outcome = LockExtender.extendActiveLockDetailedSuspend(30.seconds)
        assert(outcome is ExtendOutcome.NotHeld) { "Expected NotHeld, got $outcome" }
    }

    @Test
    fun `extendActiveLockDetailedSuspend with lockName returns NotHeld for mismatch`() = runTest {
        val delegate = FakeDelegate(ExtendOutcome.Extended(Instant.now()))
        withContext(LockHandleElement(realHandle("lock-a", delegate))) {
            val outcome = LockExtender.extendActiveLockDetailedSuspend("lock-b", 30.seconds)
            assert(outcome is ExtendOutcome.NotHeld) { "Expected NotHeld, got $outcome" }
        }
    }
}

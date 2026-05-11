package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockAssertTest {

    // --- helpers ---

    private fun identity(name: String = "test-lock") = LockIdentity(
        lockName = name,
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "testFactory",
    )

    private fun fakeDelegate(held: Boolean = true): ExtendDelegate = object : ExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline
        override fun extend(lockAtMostFor: Duration): ExtendOutcome =
            if (held) ExtendOutcome.Extended(Instant.now()) else ExtendOutcome.NotHeld
        override fun isHeld(): Boolean = held
    }

    private fun realHandle(name: String = "test-lock"): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(name),
            token = "tok-abc",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = fakeDelegate(true),
        )

    private fun failOpenHandle(name: String = "test-lock"): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity(name))

    // --- assertLocked() sync ---

    @Test
    fun `assertLocked throws when called outside any scope`() {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLocked()
        }
    }

    @Test
    fun `assertLocked passes inside Real handle scope`() {
        LockStateHolder.withPushed(realHandle()) {
            LockAssert.assertLocked()  // must not throw
        }
    }

    @Test
    fun `assertLocked throws with fail-open message inside FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle()) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLocked()
            }
        }
    }

    // --- assertLocked(lockName) sync ---

    @Test
    fun `assertLocked with lockName passes when name matches Real handle`() {
        LockStateHolder.withPushed(realHandle("my-lock")) {
            LockAssert.assertLocked("my-lock")  // must not throw
        }
    }

    @Test
    fun `assertLocked with lockName throws when name does not match`() {
        LockStateHolder.withPushed(realHandle("my-lock")) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLocked("other-lock")
            }
        }
    }

    @Test
    fun `assertLocked with lockName throws outside any scope`() {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLocked("some-lock")
        }
    }

    @Test
    fun `assertLocked with lockName throws inside FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle("fo-lock")) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLocked("fo-lock")
            }
        }
    }

    // --- isLocked() sync ---

    @Test
    fun `isLocked returns false outside any scope`() {
        LockAssert.isLocked().shouldBeFalse()
    }

    @Test
    fun `isLocked returns true inside Real handle scope`() {
        LockStateHolder.withPushed(realHandle()) {
            LockAssert.isLocked().shouldBeTrue()
        }
    }

    @Test
    fun `isLocked returns false inside FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle()) {
            LockAssert.isLocked().shouldBeFalse()
        }
    }

    // --- isLocked(lockName) sync ---

    @Test
    fun `isLocked with lockName returns true when name matches Real handle`() {
        LockStateHolder.withPushed(realHandle("lock-a")) {
            LockAssert.isLocked("lock-a").shouldBeTrue()
        }
    }

    @Test
    fun `isLocked with lockName returns false when name does not match`() {
        LockStateHolder.withPushed(realHandle("lock-a")) {
            LockAssert.isLocked("lock-b").shouldBeFalse()
        }
    }

    @Test
    fun `isLocked with lockName returns false inside FailOpen scope`() {
        LockStateHolder.withPushed(failOpenHandle("fo-lock")) {
            LockAssert.isLocked("fo-lock").shouldBeFalse()
        }
    }

    // --- assertLockedSuspend() suspend ---

    @Test
    fun `assertLockedSuspend passes with Real handle in coroutineContext`() = runTest {
        withContext(LockHandleElement(realHandle())) {
            LockAssert.assertLockedSuspend()  // must not throw
        }
    }

    @Test
    fun `assertLockedSuspend throws with FailOpen handle in coroutineContext`() = runTest {
        withContext(LockHandleElement(failOpenHandle())) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLockedSuspend()
            }
        }
    }

    @Test
    fun `assertLockedSuspend throws when no LockHandleElement in coroutineContext`() = runTest {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLockedSuspend()
        }
    }

    /**
     * R7 regression — ThreadLocal fallback 제거 검증.
     *
     * sync ThreadLocal 에 Real handle 을 push 한 thread 에서 assertLockedSuspend() 를 호출해도
     * coroutineContext 에 LockHandleElement 가 없으면 반드시 throw 해야 한다.
     * (carrier thread ThreadLocal 이 그대로 읽힌다면 잘못된 pass-through 발생)
     */
    @Test
    fun `assertLockedSuspend throws even when only ThreadLocal is set (R7 no fallback)`() = runTest {
        LockStateHolder.withPushed(realHandle()) {
            // ThreadLocal has Real handle, but coroutineContext has no LockHandleElement
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLockedSuspend()
            }
        }
    }

    // --- assertLockedSuspend(lockName) suspend ---

    @Test
    fun `assertLockedSuspend with lockName passes when names match`() = runTest {
        withContext(LockHandleElement(realHandle("named-lock"))) {
            LockAssert.assertLockedSuspend("named-lock")  // must not throw
        }
    }

    @Test
    fun `assertLockedSuspend with lockName throws when names mismatch`() = runTest {
        withContext(LockHandleElement(realHandle("named-lock"))) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLockedSuspend("other-lock")
            }
        }
    }

    @Test
    fun `assertLockedSuspend with lockName throws outside any scope`() = runTest {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLockedSuspend("some-lock")
        }
    }

    // --- isLockedSuspend() suspend ---

    @Test
    fun `isLockedSuspend returns true with Real handle in coroutineContext`() = runTest {
        withContext(LockHandleElement(realHandle())) {
            LockAssert.isLockedSuspend().shouldBeTrue()
        }
    }

    @Test
    fun `isLockedSuspend returns false without LockHandleElement`() = runTest {
        LockAssert.isLockedSuspend().shouldBeFalse()
    }

    @Test
    fun `isLockedSuspend returns false with FailOpen handle`() = runTest {
        withContext(LockHandleElement(failOpenHandle())) {
            LockAssert.isLockedSuspend().shouldBeFalse()
        }
    }

    // --- isLockedSuspend(lockName) suspend ---

    @Test
    fun `isLockedSuspend with lockName returns true when names match`() = runTest {
        withContext(LockHandleElement(realHandle("lock-x"))) {
            LockAssert.isLockedSuspend("lock-x").shouldBeTrue()
        }
    }

    @Test
    fun `isLockedSuspend with lockName returns false when names mismatch`() = runTest {
        withContext(LockHandleElement(realHandle("lock-x"))) {
            LockAssert.isLockedSuspend("lock-y").shouldBeFalse()
        }
    }
}

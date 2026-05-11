package io.bluetape4k.leader

import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderLockHandleTest {

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

    private fun realHandle(
        name: String = "test-lock",
        token: String = "tok-abc",
        depth: Int = 0,
        held: Boolean = true,
    ): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(name),
            token = token,
            acquiredAtNanos = System.nanoTime(),
            reentryDepth = depth,
            extendDelegate = fakeDelegate(held),
        )

    // --- lockName / isReentrant ---

    @Test
    fun `lockName delegates to identity lockName`() {
        val handle = realHandle(name = "my-lock")
        handle.lockName shouldBeEqualTo "my-lock"
    }

    @Test
    fun `isReentrant is false for depth 0`() {
        realHandle(depth = 0).isReentrant.shouldBeFalse()
    }

    @Test
    fun `isReentrant is true for depth greater than 0`() {
        realHandle(depth = 1).isReentrant.shouldBeTrue()
    }

    // --- matchesIdentity ---

    @Test
    fun `matchesIdentity returns true for same lockName and kind`() {
        val handle = realHandle(name = "lock-x")
        val other = identity("lock-x")
        handle.matchesIdentity(other).shouldBeTrue()
    }

    @Test
    fun `matchesIdentity returns false for different lockName`() {
        val handle = realHandle(name = "lock-x")
        val other = identity("lock-y")
        handle.matchesIdentity(other).shouldBeFalse()
    }

    // --- extend / extendSuspend / isStillHeld ---

    @Test
    fun `extend returns Extended when backend is held`() {
        val handle = realHandle(held = true)
        handle.extend(30.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `extend returns NotHeld when backend is not held`() {
        val handle = realHandle(held = false)
        handle.extend(30.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
    }

    @Test
    fun `extendSuspend returns Extended when backend is held`() = runTest {
        val handle = realHandle(held = true)
        handle.extendSuspend(30.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `isStillHeld returns true when delegate is held`() {
        realHandle(held = true).isStillHeld().shouldBeTrue()
    }

    @Test
    fun `isStillHeld returns false when delegate is not held`() {
        realHandle(held = false).isStillHeld().shouldBeFalse()
    }

    // --- withReentryDepth — shared extendDelegate (R5-F3 / SF11) ---

    @Test
    fun `withReentryDepth copies share the same extendDelegate instance`() {
        val handle = realHandle(depth = 0)
        val inner = handle.withReentryDepth(1)
        // Both share the same delegate — both return Extended (same backend)
        handle.extend(30.seconds).isExtended.shouldBeTrue()
        inner.extend(30.seconds).isExtended.shouldBeTrue()
        // Structural: inner reentryDepth is 1, outer is 0
        inner.reentryDepth shouldBeEqualTo 1
        handle.reentryDepth shouldBeEqualTo 0
    }

    @Test
    fun `withReentryDepth preserves identity and token`() {
        val handle = realHandle(name = "shared-lock", token = "tok-xyz")
        val inner = handle.withReentryDepth(2)
        inner.identity shouldBeEqualTo handle.identity
        inner.token shouldBeEqualTo handle.token
    }

    // --- equals / hashCode ---

    @Test
    fun `Real equals based on identity, token, reentryDepth, slotId`() {
        val delegate = fakeDelegate()
        val id = identity()
        val h1 = LeaderLockHandle.real(id, "tok", System.nanoTime(), reentryDepth = 0, extendDelegate = delegate)
        val h2 = LeaderLockHandle.real(id, "tok", System.nanoTime() + 1000, reentryDepth = 0, extendDelegate = delegate)
        // acquiredAtNanos differs but equals ignores it
        (h1 == h2).shouldBeTrue()
    }

    @Test
    fun `Real not equals when token differs`() {
        val delegate = fakeDelegate()
        val id = identity()
        val h1 = LeaderLockHandle.real(id, "tok-A", System.nanoTime(), reentryDepth = 0, extendDelegate = delegate)
        val h2 = LeaderLockHandle.real(id, "tok-B", System.nanoTime(), reentryDepth = 0, extendDelegate = delegate)
        (h1 == h2).shouldBeFalse()
    }

    // --- FailOpen ---

    @Test
    fun `FailOpen lockName delegates to identity lockName`() {
        val fo = LeaderLockHandle.failOpen(identity("fo-lock"))
        fo.lockName shouldBeEqualTo "fo-lock"
    }

    @Test
    fun `FailOpen reentryDepth is always 0`() {
        LeaderLockHandle.failOpen(identity()).reentryDepth shouldBeEqualTo 0
    }

    @Test
    fun `FailOpen equals by identity`() {
        val id = identity()
        val fo1 = LeaderLockHandle.failOpen(id)
        val fo2 = LeaderLockHandle.failOpen(id)
        (fo1 == fo2).shouldBeTrue()
    }
}

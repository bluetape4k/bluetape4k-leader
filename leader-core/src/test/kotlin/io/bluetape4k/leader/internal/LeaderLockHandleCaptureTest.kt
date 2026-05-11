package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderLockHandleCaptureTest {

    private fun identity() = LockIdentity(
        lockName = "cap-test-lock",
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "testFactory",
    )

    private fun fakeDelegate(): ExtendDelegate = object : ExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline
        override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Extended(Instant.now())
        override fun isHeld(): Boolean = true
    }

    private fun realHandle(): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(),
            token = "tok-capture",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = fakeDelegate(),
        )

    @Test
    fun `poll returns null when nothing was set`() {
        // ensure clean state
        LeaderLockHandleCapture.clear()
        LeaderLockHandleCapture.poll().shouldBeNull()
    }

    @Test
    fun `set then poll returns the handle`() {
        val handle = realHandle()
        LeaderLockHandleCapture.set(handle)
        LeaderLockHandleCapture.poll() shouldBeEqualTo handle
    }

    @Test
    fun `poll clears the ThreadLocal immediately — second poll returns null`() {
        val handle = realHandle()
        LeaderLockHandleCapture.set(handle)
        LeaderLockHandleCapture.poll()  // first poll consumes
        LeaderLockHandleCapture.poll().shouldBeNull()  // second must be null
    }

    @Test
    fun `clear removes the value — poll after clear returns null`() {
        val handle = realHandle()
        LeaderLockHandleCapture.set(handle)
        LeaderLockHandleCapture.clear()
        LeaderLockHandleCapture.poll().shouldBeNull()
    }
}

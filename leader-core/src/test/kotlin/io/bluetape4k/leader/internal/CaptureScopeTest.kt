package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptureScopeTest {

    private fun identity() = LockIdentity(
        lockName = "scope-test-lock",
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
            token = "tok-scope",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = fakeDelegate(),
        )

    @BeforeEach
    fun cleanCapture() {
        LeaderLockHandleCapture.clear()
    }

    // --- sync ---

    @Test
    fun `runWithCapture - handle is pollable inside action`() {
        val handle = realHandle()
        var polled: LeaderLockHandle.Real? = null

        CaptureScope.runWithCapture(handle) {
            polled = LeaderLockHandleCapture.poll()
        }

        polled shouldBeEqualTo handle
    }

    @Test
    fun `runWithCapture - clears capture after action completes`() {
        val handle = realHandle()

        CaptureScope.runWithCapture(handle) { /* do nothing */ }

        LeaderLockHandleCapture.poll().shouldBeNull()
    }

    @Test
    fun `runWithCapture - clears capture even when action throws`() {
        val handle = realHandle()

        runCatching {
            CaptureScope.runWithCapture(handle) {
                error("simulated failure")
            }
        }

        LeaderLockHandleCapture.poll().shouldBeNull()
    }

    @Test
    fun `runWithCapture - returns action result`() {
        val handle = realHandle()

        val result = CaptureScope.runWithCapture(handle) { 42 }

        result shouldBeEqualTo 42
    }
}

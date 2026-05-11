package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.assertions.shouldBeEqualTo
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
class ExtendDelegateTest {

    /**
     * Minimal concrete [ExtendDelegate] that stores lastExtendDeadline as required.
     * R2 contract: the same AtomicReference instance must be returned on every access.
     */
    private class ConcreteDelegate(private val held: Boolean = true) : ExtendDelegate {
        private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
        override fun extend(lockAtMostFor: Duration): ExtendOutcome =
            if (held) ExtendOutcome.Extended(Instant.now()) else ExtendOutcome.NotHeld
        override fun isHeld(): Boolean = held
    }

    @Test
    fun `lastExtendDeadline returns same AtomicReference instance on every access (R2 contract)`() {
        val delegate = ConcreteDelegate()
        val ref1 = delegate.lastExtendDeadline
        val ref2 = delegate.lastExtendDeadline
        // Must be the exact same stored object — not a new instance each call
        (ref1 === ref2).shouldBeTrue()
    }

    @Test
    fun `set on lastExtendDeadline is visible on next get`() {
        val delegate = ConcreteDelegate()
        val deadline = Instant.now().plusSeconds(60)
        delegate.lastExtendDeadline.set(deadline)
        delegate.lastExtendDeadline.get() shouldBeEqualTo deadline
    }

    @Test
    fun `extend returns Extended when held`() {
        val delegate = ConcreteDelegate(held = true)
        delegate.extend(30.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `extend returns NotHeld when not held`() {
        val delegate = ConcreteDelegate(held = false)
        delegate.extend(30.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
    }

    @Test
    fun `extendSuspend default delegates to sync extend`() = runTest {
        val delegate = ConcreteDelegate(held = true)
        delegate.extendSuspend(30.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `isHeld reflects backend state`() {
        ConcreteDelegate(held = true).isHeld().shouldBeTrue()
    }
}

package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class MonotonicDeadlineTest {

    @Test
    fun `remaining delay uses monotonic elapsed time`() {
        var tickerNanos = 1_000_000_000L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 50L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 80.milliseconds.inWholeNanoseconds

        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 20L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 20.milliseconds.inWholeNanoseconds

        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `positive sub millisecond budget keeps one millisecond delay window`() {
        var tickerNanos = 1_000_000L
        val deadline = MonotonicDeadline.fromNow(1.milliseconds) { tickerNanos }

        tickerNanos += 999_500L

        deadline.remainingNanos() shouldBeEqualTo 500L
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 1L
        deadline.hasTimeRemaining().shouldBeTrue()
    }

    @Test
    fun `zero wait time is already expired`() {
        val deadline = MonotonicDeadline.fromNow(0.milliseconds) { 42L }

        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `remainingMillisForDelay - max delay must be positive`() {
        val deadline = MonotonicDeadline.fromNow(1.milliseconds) { 42L }

        assertFailsWith<IllegalArgumentException> {
            deadline.remainingMillisForDelay(0L)
        }
    }

    @Test
    fun `negative wait time is already expired`() {
        val deadline = MonotonicDeadline.fromNow((-1).milliseconds) { 42L }

        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `fromNow - huge wait time saturates deadline instead of overflowing`() {
        var tickerNanos = Long.MAX_VALUE - 10L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingNanos() shouldBeEqualTo 10L
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 1L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 10L

        deadline.remainingNanos() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }
}

package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class MonotonicDeadlineTest {

    @Test
    fun `remainingMillisForSleep - 단조 시간 경과량으로 남은 시간을 계산한다`() {
        var tickerNanos = 1_000_000_000L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingMillisForSleep() shouldBeEqualTo 100L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 40.milliseconds.inWholeNanoseconds

        deadline.remainingMillisForSleep() shouldBeEqualTo 60L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 60.milliseconds.inWholeNanoseconds

        deadline.remainingMillisForSleep() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `remainingMillisForSleep - sub millisecond budget keeps one millisecond sleep window`() {
        var tickerNanos = 1_000_000L
        val deadline = MonotonicDeadline.fromNow(1.milliseconds) { tickerNanos }

        tickerNanos += 999_500L

        deadline.remainingNanos() shouldBeEqualTo 500L
        deadline.remainingMillisForSleep() shouldBeEqualTo 1L
        deadline.hasTimeRemaining().shouldBeTrue()
    }

    @Test
    fun `fromNow - zero wait time is already expired`() {
        val deadline = MonotonicDeadline.fromNow(0.milliseconds) { 42L }

        deadline.remainingMillisForSleep() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `fromNow - negative wait time is already expired`() {
        val deadline = MonotonicDeadline.fromNow((-1).milliseconds) { 42L }

        deadline.remainingMillisForSleep() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }
}

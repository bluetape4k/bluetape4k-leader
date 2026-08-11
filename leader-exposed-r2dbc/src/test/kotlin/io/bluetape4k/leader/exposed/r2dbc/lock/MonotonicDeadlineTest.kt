package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.exposed.r2dbc.internal.MonotonicDeadline
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
    fun `remainingMillisForSleep - 임의의 음수 origin에서도 monotonic budget을 유지한다`() {
        var tickerNanos = -5_000_000_000L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingMillisForSleep() shouldBeEqualTo 100L

        tickerNanos += 40.milliseconds.inWholeNanoseconds

        deadline.remainingMillisForSleep() shouldBeEqualTo 60L
        deadline.hasTimeRemaining().shouldBeTrue()
    }

    @Test
    fun `remainingMillisForSleep - sub millisecond budget은 마지막 1ms sleep 창을 보존한다`() {
        var tickerNanos = 1_000_000L
        val deadline = MonotonicDeadline.fromNow(1.milliseconds) { tickerNanos }

        tickerNanos += 999_500L

        deadline.remainingNanos() shouldBeEqualTo 500L
        deadline.remainingMillisForSleep() shouldBeEqualTo 1L
        deadline.hasTimeRemaining().shouldBeTrue()
    }

    @Test
    fun `fromNow - zero와 negative wait time은 즉시 만료된다`() {
        MonotonicDeadline.fromNow(0.milliseconds) { 42L }
            .hasTimeRemaining().shouldBeFalse()
        MonotonicDeadline.fromNow((-1).milliseconds) { 42L }
            .hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `fromNow - ticker wrap 경계에서도 전체 wait budget을 보존한다`() {
        var tickerNanos = Long.MAX_VALUE - 10L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }
        val timeoutNanos = 100.milliseconds.inWholeNanoseconds

        deadline.remainingNanos() shouldBeEqualTo timeoutNanos
        deadline.remainingMillisForSleep() shouldBeEqualTo 100L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 10L
        deadline.remainingNanos() shouldBeEqualTo timeoutNanos - 10L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 1L
        deadline.remainingNanos() shouldBeEqualTo timeoutNanos - 11L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += timeoutNanos - 11L
        deadline.remainingNanos() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }
}

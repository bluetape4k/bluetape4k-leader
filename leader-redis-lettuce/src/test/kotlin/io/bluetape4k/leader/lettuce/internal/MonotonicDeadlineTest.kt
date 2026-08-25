package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineMathContractTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MonotonicDeadlineTest: AbstractMonotonicDeadlineMathContractTest() {

    override fun createDeadline(waitTime: Duration, ticker: () -> Long): DeadlineProbe {
        val deadline = MonotonicDeadline.fromNow(waitTime, ticker)
        return object: DeadlineProbe {
            override fun remainingNanos(): Long = deadline.remainingNanos()
            override fun remainingMillisForDelay(maxDelayMillis: Long): Long = deadline.remainingMillisForDelay(maxDelayMillis)
            override fun hasTimeRemaining(): Boolean = deadline.hasTimeRemaining()
        }
    }

    @Test
    fun `park delay follows the remaining budget`() {
        var tickerNanos = 1_000_000_000L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 50.milliseconds.inWholeNanoseconds

        tickerNanos += 80.milliseconds.inWholeNanoseconds

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 20.milliseconds.inWholeNanoseconds

        tickerNanos += 20.milliseconds.inWholeNanoseconds

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 0L
    }

    @Test
    fun `park delay remains capped after ticker wrap`() {
        var tickerNanos = Long.MAX_VALUE - 10L
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 50.milliseconds.inWholeNanoseconds

        tickerNanos += 11L

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 50.milliseconds.inWholeNanoseconds

        tickerNanos += 100.milliseconds.inWholeNanoseconds

        deadline.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 0L
    }

    @Test
    fun `park delay is zero for non-positive wait`() {
        val zero = MonotonicDeadline.fromNow(Duration.ZERO) { 42L }
        val negative = MonotonicDeadline.fromNow((-1).milliseconds) { 42L }

        zero.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 0L
        negative.remainingNanosForPark(50.milliseconds.inWholeNanoseconds) shouldBeEqualTo 0L
    }
}

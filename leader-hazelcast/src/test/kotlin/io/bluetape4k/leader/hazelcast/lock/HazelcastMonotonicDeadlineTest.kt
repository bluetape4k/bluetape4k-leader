package io.bluetape4k.leader.hazelcast.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineMathContractTest
import io.bluetape4k.leader.internal.MonotonicDeadline
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class HazelcastMonotonicDeadlineTest: AbstractMonotonicDeadlineMathContractTest() {

    override fun createDeadline(waitTime: Duration, ticker: () -> Long): DeadlineProbe {
        val deadline = MonotonicDeadline.fromNow(waitTime, ticker)
        return object: DeadlineProbe {
            override fun remainingNanos(): Long = deadline.remainingNanos()
            override fun remainingMillisForDelay(maxDelayMillis: Long): Long =
                deadline.remainingMillisForDelay(maxDelayMillis)

            override fun hasTimeRemaining(): Boolean = deadline.hasTimeRemaining()
        }
    }

    @Test
    fun `wall clock 전진과 후퇴는 monotonic wait budget을 변경하지 않는다`() {
        var tickerNanos = 1_000_000_000L
        var wallClock = Instant.parse("2026-01-01T00:00:00Z")
        val deadline = MonotonicDeadline.fromNow(100.milliseconds) { tickerNanos }

        wallClock = wallClock.plusSeconds(3_600L)
        wallClock shouldBeEqualTo Instant.parse("2026-01-01T01:00:00Z")
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 50L

        wallClock = wallClock.minusSeconds(7_200L)
        wallClock shouldBeEqualTo Instant.parse("2025-12-31T23:00:00Z")
        deadline.remainingNanos() shouldBeEqualTo 100.milliseconds.inWholeNanoseconds

        tickerNanos += 40.milliseconds.inWholeNanoseconds
        deadline.remainingNanos() shouldBeEqualTo 60.milliseconds.inWholeNanoseconds
    }
}

package io.bluetape4k.leader.hazelcast.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.inNanos
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineMathContractTest
import io.bluetape4k.leader.internal.MonotonicDeadline
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class HazelcastMonotonicDeadlineTest: AbstractMonotonicDeadlineMathContractTest() {

    companion object: KLogging()

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

        wallClock += 3_600L.seconds()
        wallClock shouldBeEqualTo Instant.parse("2026-01-01T01:00:00Z")
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 50L

        wallClock -= 7_200L.seconds()
        wallClock shouldBeEqualTo Instant.parse("2025-12-31T23:00:00Z")
        deadline.remainingNanos() shouldBeEqualTo 100.millis().inNanos()

        tickerNanos += 40.millis().inNanos()
        deadline.remainingNanos() shouldBeEqualTo 60.millis().inNanos()
    }
}

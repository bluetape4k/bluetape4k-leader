package io.bluetape4k.leader.dynamodb.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {
    fun hasTimeRemaining(): Boolean = deadlineNanos - ticker() > 0L

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        require(maxDelayMillis >= 1L) { "maxDelayMillis must be at least 1" }
        val remaining = deadlineNanos - ticker()
        if (remaining <= 0L) {
            return 0L
        }
        return TimeUnit.NANOSECONDS.toMillis(remaining)
            .coerceAtLeast(1L)
            .coerceAtMost(maxDelayMillis)
    }

    companion object {
        fun fromNow(waitTime: Duration, ticker: () -> Long = System::nanoTime): MonotonicDeadline {
            val now = ticker()
            val timeout = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            val deadline =
                if (Long.MAX_VALUE - timeout < now) Long.MAX_VALUE else now + timeout
            return MonotonicDeadline(deadline, ticker)
        }
    }
}

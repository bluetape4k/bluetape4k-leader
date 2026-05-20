package io.bluetape4k.leader.mongodb.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Monotonic timeout budget for local MongoDB acquisition retry loops.
 *
 * MongoDB lease expiry remains wall-clock based because `expireAt` is persisted
 * and compared by the database. Client-side waits use [System.nanoTime] so wall
 * clock adjustments do not extend or shorten `tryLock` retry budgets.
 */
internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {

    fun remainingNanos(): Long = deadlineNanos - ticker()

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        require(maxDelayMillis >= 1L) { "maxDelayMillis must be at least 1" }

        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos)
            .coerceAtLeast(1L)
            .coerceAtMost(maxDelayMillis)
    }

    fun hasTimeRemaining(): Boolean = remainingNanos() > 0L

    companion object {
        fun fromNow(
            waitTime: Duration,
            ticker: () -> Long = System::nanoTime,
        ): MonotonicDeadline {
            val now = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            val deadlineNanos =
                if (Long.MAX_VALUE - timeoutNanos < now) {
                    Long.MAX_VALUE
                } else {
                    now + timeoutNanos
                }
            return MonotonicDeadline(deadlineNanos, ticker)
        }
    }
}

package io.bluetape4k.leader.lettuce.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Monotonic timeout budget for local Redis acquisition retry loops.
 *
 * Redis lease expiry remains server-time based inside Lua scripts. Client-side
 * wait budgets use [System.nanoTime] so wall-clock adjustments do not extend or
 * shorten `tryLock` or slot-acquisition waits.
 */
internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {

    fun remainingNanos(): Long = deadlineNanos - ticker()

    fun remainingNanosForPark(maxDelayNanos: Long): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return remainingNanos.coerceAtMost(maxDelayNanos)
    }

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
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

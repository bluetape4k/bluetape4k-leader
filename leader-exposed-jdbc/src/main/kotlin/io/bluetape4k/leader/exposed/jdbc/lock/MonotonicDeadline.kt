package io.bluetape4k.leader.exposed.jdbc.lock

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Monotonic timeout budget for local retry loops.
 *
 * Database lease timestamps remain wall-clock based because other JVMs compare
 * them through the database. Local wait budgets use [System.nanoTime] so wall
 * clock adjustments do not extend or shorten a caller's `tryLock` wait.
 */
internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {

    fun remainingNanos(): Long = deadlineNanos - ticker()

    fun remainingMillisForSleep(): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        // RetryStrategy and Thread.sleep accept milliseconds; keep a positive
        // sub-millisecond budget observable as one last sleep window.
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
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

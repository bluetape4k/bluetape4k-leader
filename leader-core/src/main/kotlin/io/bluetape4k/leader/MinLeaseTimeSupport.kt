package io.bluetape4k.leader

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Returns the remaining minimum lease duration from [startedAtNanos].
 *
 * Backend adapters use this value to delegate lock retention to their storage TTL
 * instead of blocking caller threads after a fast action returns.
 */
fun remainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration): Duration {
    if (minLeaseTime <= Duration.ZERO) {
        return Duration.ZERO
    }
    val elapsed = (System.nanoTime() - startedAtNanos).nanoseconds
    val remaining = minLeaseTime - elapsed
    return if (remaining > Duration.ZERO) remaining else Duration.ZERO
}

internal fun parkRemainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration) {
    val remaining = remainingMinLeaseTime(startedAtNanos, minLeaseTime)
    if (remaining > Duration.ZERO) {
        LockSupport.parkNanos(remaining.inWholeNanoseconds)
    }
}

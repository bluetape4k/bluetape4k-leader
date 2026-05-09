package io.bluetape4k.leader

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal fun remainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration): Duration {
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

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

/**
 * Parks the calling thread for the remaining minimum lease time.
 *
 * ⚠️ **BLOCKING CALL** — must NOT be invoked from coroutine dispatcher threads or virtual threads
 * managed by a coroutine scheduler. Only safe for platform threads in sync electors
 * (e.g., [LeaderElector], [VirtualThreadLeaderElector]).
 *
 * Backend adapters that need TTL-based retention should use [remainingMinLeaseTime] to update
 * the backend TTL directly instead of calling this function.
 */
@Suppress("BlockingMethodInNonBlockingContext")
internal fun parkRemainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration) {
    val remaining = remainingMinLeaseTime(startedAtNanos, minLeaseTime)
    if (remaining > Duration.ZERO) {
        LockSupport.parkNanos(remaining.inWholeNanoseconds)
    }
}

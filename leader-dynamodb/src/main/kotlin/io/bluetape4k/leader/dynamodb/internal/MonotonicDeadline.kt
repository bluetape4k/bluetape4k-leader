package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.internal.MonotonicDeadline as CoreMonotonicDeadline
import kotlin.time.Duration

internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {

    private var delegate = CoreMonotonicDeadline.fromStart(deadlineNanos, 0L, ticker)

    private fun withTimeout(timeoutNanos: Long): MonotonicDeadline {
        delegate = CoreMonotonicDeadline.fromStart(deadlineNanos, timeoutNanos, ticker)
        return this
    }
    fun remainingNanos(): Long = delegate.remainingNanos()

    fun hasTimeRemaining(): Boolean = delegate.hasTimeRemaining()

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        require(maxDelayMillis >= 1L) { "maxDelayMillis must be at least 1" }
        return delegate.remainingMillisForDelay(maxDelayMillis)
    }

    companion object {
        fun fromNow(waitTime: Duration, ticker: () -> Long = System::nanoTime): MonotonicDeadline {
            val startNanos = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            return MonotonicDeadline(startNanos, ticker).withTimeout(timeoutNanos)
        }
    }
}

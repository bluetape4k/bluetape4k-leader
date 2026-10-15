package io.bluetape4k.leader.mongodb.internal

import kotlin.time.Duration
import io.bluetape4k.leader.internal.MonotonicDeadline as CoreMonotonicDeadline

/**
 * `MonotonicDeadline`는 monotonic clock 기준으로 MongoDB 호출의 남은 deadline과 delay 상한을 계산해 wall-clock 변경의 영향을 피합니다.
 *
 */
internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {

    private var delegate = CoreMonotonicDeadline
        .fromStart(deadlineNanos, 0L, ticker)

    private fun withTimeout(timeoutNanos: Long): MonotonicDeadline = apply {
        delegate = CoreMonotonicDeadline.fromStart(deadlineNanos, timeoutNanos, ticker)
    }

    fun remainingNanos(): Long = delegate.remainingNanos()

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        require(maxDelayMillis >= 1L) { "maxDelayMillis must be at least 1" }
        return delegate.remainingMillisForDelay(maxDelayMillis)
    }

    fun hasTimeRemaining(): Boolean = delegate.hasTimeRemaining()

    companion object {
        fun fromNow(
            waitTime: Duration,
            ticker: () -> Long = System::nanoTime,
        ): MonotonicDeadline {
            val startNanos = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            return MonotonicDeadline(startNanos, ticker).withTimeout(timeoutNanos)
        }
    }
}

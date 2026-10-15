package io.bluetape4k.leader.lettuce.internal

import kotlin.time.Duration
import io.bluetape4k.leader.internal.MonotonicDeadline as CoreMonotonicDeadline

/**
 * `MonotonicDeadline`는 monotonic clock 기준으로 Redis Lettuce 호출의 남은 deadline과 delay 상한을 계산해 wall-clock 변경의 영향을 피합니다.
 *
 */
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

    fun remainingNanosForPark(maxDelayNanos: Long): Long = delegate.remainingNanosForPark(maxDelayNanos)

    fun remainingMillisForDelay(maxDelayMillis: Long): Long = delegate.remainingMillisForDelay(maxDelayMillis)

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

package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.internal.MonotonicDeadline as CoreMonotonicDeadline
import kotlin.time.Duration

/**
 * `MonotonicDeadline`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
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

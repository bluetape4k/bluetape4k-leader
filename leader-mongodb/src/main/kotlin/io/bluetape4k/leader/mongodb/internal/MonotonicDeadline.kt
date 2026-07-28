package io.bluetape4k.leader.mongodb.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * `MonotonicDeadline`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property deadlineNanos MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property ticker MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
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

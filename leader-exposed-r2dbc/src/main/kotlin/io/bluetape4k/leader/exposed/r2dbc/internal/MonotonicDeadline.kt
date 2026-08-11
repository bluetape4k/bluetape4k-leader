package io.bluetape4k.leader.exposed.r2dbc.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * R2DBC suspend 재시도 루프에서 벽시계 보정과 무관하게 wait budget을 계산합니다.
 *
 * `delay`가 밀리초만 받으므로 sub-millisecond 예산도 마지막 1ms 창으로 보존합니다.
 * `System.nanoTime()`의 임의 origin과 wrap-around를 고려해 절대 deadline을 만들지
 * 않고, 시작 시각과 경과 시간의 차이로 남은 예산을 계산합니다.
 */
internal class MonotonicDeadline private constructor(
    private val startNanos: Long,
    private val timeoutNanos: Long,
    private val ticker: () -> Long,
) {

    private fun elapsedNanos(): Long = (ticker() - startNanos).coerceAtLeast(0L)

    fun remainingNanos(): Long {
        val elapsedNanos = elapsedNanos()
        return if (elapsedNanos >= timeoutNanos) {
            0L
        } else {
            timeoutNanos - elapsedNanos
        }
    }

    fun remainingMillisForSleep(): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
    }

    fun hasTimeRemaining(): Boolean = elapsedNanos() < timeoutNanos

    companion object {
        fun fromNow(
            waitTime: Duration,
            ticker: () -> Long = System::nanoTime,
        ): MonotonicDeadline {
            val startNanos = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            return MonotonicDeadline(startNanos, timeoutNanos, ticker)
        }
    }
}

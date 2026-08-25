package io.bluetape4k.leader.internal

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * 여러 leader backend가 공유하는 monotonic wait budget 계산기입니다.
 *
 * `System.nanoTime()`의 임의 origin과 Long wrap-around를 안전하게 처리하기 위해
 * 절대 deadline을 저장하지 않고 시작 시각과 timeout의 경과 차이로 남은 예산을 계산합니다.
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
class MonotonicDeadline private constructor(
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

    fun remainingNanosForPark(maxDelayNanos: Long): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return remainingNanos.coerceAtMost(maxDelayNanos)
    }

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos)
            .coerceAtLeast(1L)
            .coerceAtMost(maxDelayMillis)
    }

    fun remainingMillisForSleep(): Long {
        val remainingNanos = remainingNanos()
        if (remainingNanos <= 0L) {
            return 0L
        }
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
    }

    fun hasTimeRemaining(): Boolean = remainingNanos() > 0L

    companion object {
        /**
         * 이미 읽은 시작 시각과 timeout으로 deadline을 만듭니다.
         *
         * backend wrapper가 기존 JVM constructor 모양을 유지하면서도 공통 계산기를
         * 위임할 수 있도록 제공하는 내부 생태계용 factory입니다.
         */
        fun fromStart(
            startNanos: Long,
            timeoutNanos: Long,
            ticker: () -> Long = System::nanoTime,
        ): MonotonicDeadline {
            return MonotonicDeadline(startNanos, timeoutNanos.coerceAtLeast(0L), ticker)
        }

        fun fromNow(
            waitTime: Duration,
            ticker: () -> Long = System::nanoTime,
        ): MonotonicDeadline {
            val startNanos = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            return fromStart(startNanos, timeoutNanos, ticker)
        }
    }
}

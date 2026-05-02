package io.bluetape4k.leader.exposed.jdbc

import java.util.concurrent.ThreadLocalRandom

/**
 * 락 획득 재시도 대기 전략을 정의하는 sealed class.
 *
 * 각 전략의 [delayMs] 반환값은 항상 `0 < result <= remaining` 범위를 보장합니다.
 * `remaining` 파라미터로 deadline 초과를 방지합니다.
 *
 * ```kotlin
 * val strategy = RetryStrategy.Jitter(baseDelayMs = 50L)
 * val delay = strategy.delayMs(attempt = 0, remaining = 1000L)
 * Thread.sleep(delay)
 * ```
 */
sealed class RetryStrategy {

    /**
     * 시도 횟수와 남은 시간을 기반으로 대기 시간(밀리초)을 계산합니다.
     *
     * @param attempt 현재 재시도 횟수 (0-based)
     * @param remaining deadline까지 남은 밀리초. 반환값이 이 값을 초과하지 않음을 보장
     * @return 대기 시간 (밀리초). 항상 `1 <= result <= remaining`
     */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /**
     * AWS full jitter 재시도 전략.
     *
     * `[1ms, baseDelayMs)` 균등 분포에서 랜덤 값을 선택합니다.
     * 동일 재시도 윈도우에 인스턴스가 집중되는 것을 방지합니다.
     *
     * @property baseDelayMs jitter 상한 (기본값 50ms)
     */
    data class Jitter(val baseDelayMs: Long = 50L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long {
            val upper = baseDelayMs.coerceAtLeast(2L)
            return ThreadLocalRandom.current().nextLong(1L, upper).coerceAtMost(remaining)
        }
    }

    /**
     * 지수 백오프 재시도 전략.
     *
     * `baseDelayMs * 2^attempt` 를 최대 `maxDelayMs`까지 증가시킵니다.
     * `attempt`는 내부적으로 10으로 클램프하여 오버플로를 방지합니다.
     *
     * @property baseDelayMs 기본 대기 시간 (기본값 50ms)
     * @property maxDelayMs 최대 대기 시간 (기본값 5000ms)
     */
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long {
            val capped = attempt.coerceAtMost(10)
            val delay = (baseDelayMs * (1L shl capped)).coerceAtMost(maxDelayMs)
            return delay.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }

    /**
     * 고정 간격 재시도 전략.
     *
     * 매 재시도마다 동일한 대기 시간을 사용합니다.
     *
     * @property fixedMs 고정 대기 시간 (기본값 50ms)
     */
    data class Fixed(val fixedMs: Long = 50L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long =
            fixedMs.coerceAtMost(remaining).coerceAtLeast(1L)
    }
}

package io.bluetape4k.leader.exposed.jdbc

import java.io.Serializable
import java.util.concurrent.ThreadLocalRandom

/**
 * 락 획득 재시도 대기 전략을 정의하는 sealed class.
 *
 * 락 구현체(`ExposedJdbcLock`, `ExposedJdbcGroupLock`)가 내부적으로 사용하는 재시도 전략입니다.
 * 일반 사용자는 [ExposedJdbcLeaderElectionOptions.retryStrategy]를 통해 전략을 선택하기만 하면 됩니다.
 *
 * ### 예시
 * ```kotlin
 * val options = ExposedJdbcLeaderElectionOptions(
 *     retryStrategy = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 1_000L),
 * )
 * val election = ExposedJdbcLeaderElection(db, options)
 * ```
 *
 * ### 계약 (모든 변형 공통)
 * - `remaining > 0`일 때: `1 <= delayMs(attempt, remaining) <= remaining`.
 * - `remaining <= 0`일 때: `delayMs`는 `0`을 반환할 수 있으나, 호출자가 `remaining > 0`을
 *   먼저 검증하므로 실제로 사용되지 않습니다.
 */
sealed class RetryStrategy : Serializable {

    /**
     * 시도 횟수와 남은 시간을 기반으로 대기 시간(밀리초)을 계산합니다.
     *
     * @param attempt 현재 재시도 횟수 (0-based)
     * @param remaining deadline까지 남은 밀리초. 반환값이 이 값을 초과하지 않음을 보장
     * @return 대기 시간 (밀리초). `remaining > 0`이면 `1..remaining` 범위, 그 외 `0`
     */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /**
     * AWS full jitter 재시도 전략.
     *
     * `[1ms, baseDelayMs)` 균등 분포에서 랜덤 값을 선택합니다.
     * 동일 재시도 윈도우에 인스턴스가 집중되는 것을 방지합니다.
     *
     * `baseDelayMs`가 1 이하인 경우 [IllegalArgumentException]을 발생시킵니다 (충분한 jitter 폭 확보).
     *
     * @property baseDelayMs jitter 상한 (기본값 50ms, 최소 2)
     */
    data class Jitter(val baseDelayMs: Long = 50L) : RetryStrategy() {
        init {
            require(baseDelayMs >= 2L) { "baseDelayMs must be >= 2: $baseDelayMs" }
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            return ThreadLocalRandom.current().nextLong(1L, baseDelayMs).coerceAtMost(remaining)
        }
    }

    /**
     * 지수 백오프 재시도 전략.
     *
     * `baseDelayMs * 2^attempt` 를 최대 `maxDelayMs`까지 증가시킵니다.
     * `attempt`는 내부적으로 10으로 클램프하여 오버플로를 방지합니다.
     *
     * @property baseDelayMs 기본 대기 시간 (기본값 50ms, 최소 1)
     * @property maxDelayMs 최대 대기 시간 (기본값 5000ms, `baseDelayMs` 이상)
     */
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L) : RetryStrategy() {
        init {
            require(baseDelayMs >= 1L) { "baseDelayMs must be >= 1: $baseDelayMs" }
            require(maxDelayMs >= baseDelayMs) { "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)" }
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            val capped = attempt.coerceAtLeast(0).coerceAtMost(10)
            val delay = (baseDelayMs * (1L shl capped)).coerceAtMost(maxDelayMs)
            return delay.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }

    /**
     * 고정 간격 재시도 전략.
     *
     * 매 재시도마다 동일한 대기 시간을 사용합니다.
     *
     * @property fixedMs 고정 대기 시간 (기본값 50ms, 최소 1)
     */
    data class Fixed(val fixedMs: Long = 50L) : RetryStrategy() {
        init {
            require(fixedMs >= 1L) { "fixedMs must be >= 1: $fixedMs" }
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            return fixedMs.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }
}

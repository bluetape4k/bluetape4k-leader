package io.bluetape4k.leader.exposed.retry

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import java.io.Serializable
import java.util.concurrent.ThreadLocalRandom

/**
 * `RetryStrategy`는 Exposed lock 획득 재시도마다 다음 시도까지의 지연 시간을 계산하는 정책입니다.
 *
 */
sealed class RetryStrategy: Serializable {

    companion object: KLogging() {
        private const val serialVersionUID = 1L
    }

    /**
     * 현재 재시도 횟수와 남은 대기 시간에 맞는 다음 지연을 밀리초로 계산합니다. `remaining <= 0`이면 `0`을
     * 반환하고, 그 밖에는 반환값이 남은 시간보다 클 수 없습니다.
     */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /**
     * `Jitter`는 Exposed database leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property baseDelayMs jitter를 계산할 때 사용할 상한 밀리초입니다. `2` 이상이어야 하며 기본값은 50ms입니다.
     */
    data class Jitter(val baseDelayMs: Long = 50L): RetryStrategy() {
        init {
            baseDelayMs.requireGe(2L, "baseDelayMs")
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            return ThreadLocalRandom.current().nextLong(1L, baseDelayMs).coerceAtMost(remaining)
        }
    }

    /**
     * `Exponential`는 Exposed database leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property baseDelayMs 지수 증가의 시작 지연 밀리초입니다. `1` 이상이어야 하며 기본값은 50ms입니다.
     * @property maxDelayMs 지수 증가 지연의 상한 밀리초입니다. `baseDelayMs` 이상이어야 하며 기본값은 5,000ms입니다.
     */
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L): RetryStrategy() {
        init {
            baseDelayMs.requireGe(1L, "baseDelayMs")
            maxDelayMs.requireGe(baseDelayMs, "maxDelayMs")
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            val capped = attempt.coerceAtLeast(0).coerceAtMost(10)
            val delay = (baseDelayMs * (1L shl capped)).coerceAtMost(maxDelayMs)
            return delay.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }

    /**
     * `Fixed`는 Exposed database leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property fixedMs 매 재시도에 사용할 고정 지연 밀리초입니다. `1` 이상이어야 하며 기본값은 50ms입니다.
     */
    data class Fixed(val fixedMs: Long = 50L): RetryStrategy() {
        init {
            fixedMs.requireGe(1L, "fixedMs")
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            return fixedMs.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }
}

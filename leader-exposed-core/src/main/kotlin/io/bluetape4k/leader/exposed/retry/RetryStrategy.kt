package io.bluetape4k.leader.exposed.retry

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import java.io.Serializable
import java.util.concurrent.ThreadLocalRandom

/**
 * `RetryStrategy`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
sealed class RetryStrategy : Serializable {

    companion object: KLogging() {
        private const val serialVersionUID = 1L
    }

    /**
     * `delayMs` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /**
     * `Jitter`는 Exposed database leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property baseDelayMs Exposed database backend 계약에서 `baseDelayMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Jitter(val baseDelayMs: Long = 50L) : RetryStrategy() {
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
     * @property baseDelayMs Exposed database backend 계약에서 `baseDelayMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property maxDelayMs Exposed database backend 계약에서 `maxDelayMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L) : RetryStrategy() {
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
     * @property fixedMs Exposed database backend 계약에서 `fixedMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Fixed(val fixedMs: Long = 50L) : RetryStrategy() {
        init {
            fixedMs.requireGe(1L, "fixedMs")
        }

        override fun delayMs(attempt: Int, remaining: Long): Long {
            if (remaining <= 0L) return 0L
            return fixedMs.coerceAtMost(remaining).coerceAtLeast(1L)
        }
    }
}

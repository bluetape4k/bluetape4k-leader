package io.bluetape4k.leader.exposed.retry

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import java.io.Serializable
import java.util.concurrent.ThreadLocalRandom

/**
 * Sealed class defining the wait strategy for lock acquisition retries.
 *
 * This is the retry strategy used internally by lock implementations
 * (`ExposedJdbcLock`, `ExposedR2dbcLock`, etc.).
 * Callers only need to select a strategy via `XxxLeaderElectionOptions.retryStrategy`.
 *
 * ### Contract (common to all variants)
 * - When `remaining > 0`: `1 <= delayMs(attempt, remaining) <= remaining`.
 * - When `remaining <= 0`: `delayMs` may return `0`, but this is never used in practice
 *   because callers validate `remaining > 0` first.
 */
sealed class RetryStrategy : Serializable {

    companion object: KLogging() {
        private const val serialVersionUID = 1L
    }

    /**
     * Calculates the wait duration in milliseconds based on the attempt count and remaining time.
     *
     * @param attempt current retry attempt count (0-based)
     * @param remaining milliseconds remaining until the deadline; the return value is guaranteed not to exceed this
     * @return wait duration in milliseconds; in the range `1..remaining` when `remaining > 0`, otherwise `0`
     */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /**
     * AWS full jitter retry strategy.
     *
     * Selects a random value from a uniform distribution in `[1ms, baseDelayMs)`.
     * Prevents instance stampede within the same retry window.
     *
     * @property baseDelayMs upper bound for jitter (default 50ms, minimum 2)
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
     * Exponential backoff retry strategy.
     *
     * Increases the wait by `baseDelayMs * 2^attempt` up to a maximum of `maxDelayMs`.
     *
     * @property baseDelayMs base wait duration (default 50ms, minimum 1)
     * @property maxDelayMs maximum wait duration (default 5000ms, must be >= `baseDelayMs`)
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
     * Fixed-interval retry strategy.
     *
     * @property fixedMs fixed wait duration (default 50ms, minimum 1)
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

package io.bluetape4k.leader.exposed.jdbc

import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryStrategyTest {

    @Test
    fun `Jitter - remaining이 1일 때 반환값은 1이다`() {
        val strategy = RetryStrategy.Jitter(baseDelayMs = 50L)
        val delay = strategy.delayMs(attempt = 0, remaining = 1L)
        delay shouldBeGreaterOrEqualTo 1L
        delay shouldBeLessOrEqualTo 1L
    }

    @Test
    fun `Jitter - baseDelayMs가 1일 때 IllegalArgumentException이 발생하지 않는다`() {
        val strategy = RetryStrategy.Jitter(baseDelayMs = 1L)
        val delay = strategy.delayMs(attempt = 0, remaining = 100L)
        delay shouldBeGreaterOrEqualTo 1L
        delay shouldBeLessOrEqualTo 100L
    }

    @Test
    fun `Jitter - remaining 이내로 클램프된다`() {
        val strategy = RetryStrategy.Jitter(baseDelayMs = 500L)
        repeat(20) {
            val delay = strategy.delayMs(attempt = it, remaining = 10L)
            delay shouldBeGreaterOrEqualTo 1L
            delay shouldBeLessOrEqualTo 10L
        }
    }

    @Test
    fun `Exponential - attempt가 20이어도 오버플로 없이 maxDelayMs로 클램프된다`() {
        val strategy = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        val delay = strategy.delayMs(attempt = 20, remaining = Long.MAX_VALUE)
        delay shouldBeLessOrEqualTo 5_000L
        delay shouldBeGreaterOrEqualTo 1L
    }

    @Test
    fun `Exponential - remaining 이내로 클램프된다`() {
        val strategy = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        val delay = strategy.delayMs(attempt = 5, remaining = 10L)
        delay shouldBeGreaterOrEqualTo 1L
        delay shouldBeLessOrEqualTo 10L
    }

    @Test
    fun `Fixed - fixedMs가 remaining보다 크면 remaining으로 클램프된다`() {
        val strategy = RetryStrategy.Fixed(fixedMs = 100L)
        val delay = strategy.delayMs(attempt = 0, remaining = 10L)
        delay shouldBeGreaterOrEqualTo 1L
        delay shouldBeLessOrEqualTo 10L
    }

    @Test
    fun `Fixed - remaining이 1일 때 반환값은 1이다`() {
        val strategy = RetryStrategy.Fixed(fixedMs = 50L)
        val delay = strategy.delayMs(attempt = 0, remaining = 1L)
        delay shouldBeGreaterOrEqualTo 1L
        delay shouldBeLessOrEqualTo 1L
    }

    @Test
    fun `모든 전략은 0보다 크고 remaining 이하인 값을 반환한다`() {
        val strategies = listOf(
            RetryStrategy.Jitter(baseDelayMs = 50L),
            RetryStrategy.Exponential(baseDelayMs = 50L),
            RetryStrategy.Fixed(fixedMs = 50L),
        )
        val remaining = 100L
        for (strategy in strategies) {
            for (attempt in 0..20) {
                val delay = strategy.delayMs(attempt = attempt, remaining = remaining)
                (delay in 1L..remaining).shouldBeTrue()
            }
        }
    }
}

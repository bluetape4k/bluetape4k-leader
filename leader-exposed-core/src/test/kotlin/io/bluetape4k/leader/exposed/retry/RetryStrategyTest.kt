package io.bluetape4k.leader.exposed.retry

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryStrategyTest {

    companion object: KLogging()

    // -----------------------------------------------------------------------
    // Jitter
    // -----------------------------------------------------------------------

    @Test
    fun `Jitter - 기본값 생성 성공`() {
        val s = RetryStrategy.Jitter()
        s.baseDelayMs shouldBeEqualTo 50L
    }

    @Test
    fun `Jitter - baseDelayMs 최소값(2) 생성 성공`() {
        val s = RetryStrategy.Jitter(baseDelayMs = 2L)
        s.baseDelayMs shouldBeEqualTo 2L
    }

    @Test
    fun `Jitter - baseDelayMs 1이면 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { RetryStrategy.Jitter(baseDelayMs = 1L) }
    }

    @Test
    fun `Jitter - remaining 이 0이면 0 반환`() {
        val s = RetryStrategy.Jitter()
        s.delayMs(0, 0L) shouldBeEqualTo 0L
    }

    @Test
    fun `Jitter - remaining 이 음수면 0 반환`() {
        val s = RetryStrategy.Jitter()
        s.delayMs(0, -1L) shouldBeEqualTo 0L
    }

    @Test
    fun `Jitter - delayMs 는 1 이상 min(baseDelayMs-1, remaining) 이하`() {
        val s = RetryStrategy.Jitter(baseDelayMs = 50L)
        repeat(100) {
            val delay = s.delayMs(0, 200L)
            (delay >= 1L).shouldBeTrue()
            (delay <= 49L).shouldBeTrue()
        }
    }

    @Test
    fun `Jitter - remaining 이 baseDelayMs 보다 작으면 remaining 으로 clamped`() {
        val s = RetryStrategy.Jitter(baseDelayMs = 50L)
        repeat(50) {
            val delay = s.delayMs(0, 10L)
            delay shouldBeLessOrEqualTo 10L
            delay shouldBeGreaterOrEqualTo 1L
        }
    }

    @Test
    fun `Jitter - is Serializable`() {
        val s = RetryStrategy.Jitter(30L)
        s.shouldBeInstanceOf<java.io.Serializable>()
    }

    // -----------------------------------------------------------------------
    // Exponential
    // -----------------------------------------------------------------------

    @Test
    fun `Exponential - 기본값 생성 성공`() {
        val s = RetryStrategy.Exponential()
        s.baseDelayMs shouldBeEqualTo 50L
        s.maxDelayMs shouldBeEqualTo 5_000L
    }

    @Test
    fun `Exponential - baseDelayMs 0이면 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { RetryStrategy.Exponential(baseDelayMs = 0L) }
    }

    @Test
    fun `Exponential - maxDelayMs 가 baseDelayMs 보다 작으면 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            RetryStrategy.Exponential(baseDelayMs = 100L, maxDelayMs = 50L)
        }
    }

    @Test
    fun `Exponential - remaining 이 0이면 0 반환`() {
        val s = RetryStrategy.Exponential()
        s.delayMs(0, 0L) shouldBeEqualTo 0L
    }

    @Test
    fun `Exponential - attempt 0 이면 baseDelayMs 반환`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        s.delayMs(0, 10_000L) shouldBeEqualTo 50L
    }

    @Test
    fun `Exponential - attempt 1 이면 baseDelayMs x 2 반환`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        s.delayMs(1, 10_000L) shouldBeEqualTo 100L
    }

    @Test
    fun `Exponential - attempt 2 이면 baseDelayMs x 4 반환`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        s.delayMs(2, 10_000L) shouldBeEqualTo 200L
    }

    @Test
    fun `Exponential - attempt 이 크면 maxDelayMs 에서 capped`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        // attempt=10 → 50 * 2^10 = 51_200 → capped at 5_000
        s.delayMs(10, 10_000L) shouldBeEqualTo 5_000L
    }

    @Test
    fun `Exponential - remaining 이 계산값보다 작으면 remaining 으로 clamped`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 100L, maxDelayMs = 5_000L)
        // attempt=0 → 100ms, but remaining=30 → clamped to 30
        s.delayMs(0, 30L) shouldBeEqualTo 30L
    }

    @Test
    fun `Exponential - remaining 이 1이면 1 반환`() {
        val s = RetryStrategy.Exponential()
        s.delayMs(0, 1L) shouldBeEqualTo 1L
    }

    @Test
    fun `Exponential - 음수 attempt 는 0 으로 처리`() {
        val s = RetryStrategy.Exponential(baseDelayMs = 50L, maxDelayMs = 5_000L)
        s.delayMs(-5, 10_000L) shouldBeEqualTo 50L
    }

    @Test
    fun `Exponential - is Serializable`() {
        val s = RetryStrategy.Exponential()
        s.shouldBeInstanceOf<java.io.Serializable>()
    }

    // -----------------------------------------------------------------------
    // Fixed
    // -----------------------------------------------------------------------

    @Test
    fun `Fixed - 기본값 생성 성공`() {
        val s = RetryStrategy.Fixed()
        s.fixedMs shouldBeEqualTo 50L
    }

    @Test
    fun `Fixed - fixedMs 0이면 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { RetryStrategy.Fixed(fixedMs = 0L) }
    }

    @Test
    fun `Fixed - remaining 이 0이면 0 반환`() {
        val s = RetryStrategy.Fixed()
        s.delayMs(0, 0L) shouldBeEqualTo 0L
    }

    @Test
    fun `Fixed - remaining 이 음수면 0 반환`() {
        val s = RetryStrategy.Fixed()
        s.delayMs(0, -10L) shouldBeEqualTo 0L
    }

    @Test
    fun `Fixed - delayMs 는 fixedMs 반환`() {
        val s = RetryStrategy.Fixed(fixedMs = 50L)
        s.delayMs(0, 1_000L) shouldBeEqualTo 50L
    }

    @Test
    fun `Fixed - attempt 무시`() {
        val s = RetryStrategy.Fixed(fixedMs = 50L)
        s.delayMs(100, 1_000L) shouldBeEqualTo 50L
    }

    @Test
    fun `Fixed - remaining 이 fixedMs 보다 작으면 remaining 으로 clamped`() {
        val s = RetryStrategy.Fixed(fixedMs = 50L)
        s.delayMs(0, 30L) shouldBeEqualTo 30L
    }

    @Test
    fun `Fixed - remaining 이 1이면 1 반환`() {
        val s = RetryStrategy.Fixed()
        s.delayMs(0, 1L) shouldBeEqualTo 1L
    }

    @Test
    fun `Fixed - is Serializable`() {
        val s = RetryStrategy.Fixed(100L)
        s.shouldBeInstanceOf<java.io.Serializable>()
    }

    // -----------------------------------------------------------------------
    // Sealed class 공통
    // -----------------------------------------------------------------------

    @Test
    fun `RetryStrategy sealed class 는 3가지 서브타입`() {
        val strategies: List<RetryStrategy> = listOf(
            RetryStrategy.Jitter(),
            RetryStrategy.Exponential(),
            RetryStrategy.Fixed(),
        )
        strategies.forEach {
            it shouldBeInstanceOf RetryStrategy::class
        }
    }

    @Test
    fun `모든 전략 - remaining 양수이면 delayMs 는 1 이상`() {
        val strategies = listOf(
            RetryStrategy.Jitter(baseDelayMs = 10L),
            RetryStrategy.Exponential(baseDelayMs = 10L, maxDelayMs = 100L),
            RetryStrategy.Fixed(fixedMs = 10L),
        )
        strategies.forEach { s ->
            repeat(20) {
                (s.delayMs(it, 1_000L) >= 1L).shouldBeTrue()
            }
        }
    }

    @Test
    fun `모든 전략 - delayMs 는 remaining 을 초과하지 않는다`() {
        val remaining = 25L
        val strategies = listOf(
            RetryStrategy.Jitter(baseDelayMs = 100L),
            RetryStrategy.Exponential(baseDelayMs = 100L, maxDelayMs = 1_000L),
            RetryStrategy.Fixed(fixedMs = 100L),
        )
        strategies.forEach { s ->
            repeat(20) {
                (s.delayMs(it, remaining) <= remaining).shouldBeTrue()
            }
        }
    }
}

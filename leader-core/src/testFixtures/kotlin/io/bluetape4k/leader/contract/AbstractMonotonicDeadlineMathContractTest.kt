package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 각 backend의 monotonic deadline helper가 공유해야 하는 수학 계약입니다.
 *
 * backend는 동일한 elapsed-subtraction 구현을 사용하되, delay 단위와 추가 입력
 * 검증은 각 backend의 기존 계약으로 유지할 수 있습니다.
 */
abstract class AbstractMonotonicDeadlineMathContractTest {

    protected abstract fun createDeadline(
        waitTime: Duration,
        ticker: () -> Long,
    ): DeadlineProbe

    @Test
    fun `normal elapsed budget is reduced without wall clock arithmetic`() {
        var tickerNanos = 1_000_000_000L
        val timeout = 100.milliseconds
        val deadline = createDeadline(timeout) { tickerNanos }

        tickerNanos += 80.milliseconds.inWholeNanoseconds

        deadline.remainingNanos() shouldBeEqualTo 20.milliseconds.inWholeNanoseconds
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 20L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += 20.milliseconds.inWholeNanoseconds

        deadline.remainingNanos() shouldBeEqualTo 0L
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `zero negative and sub millisecond budgets follow one boundary table`() {
        deadlineCases.forEach { case ->
            var tickerNanos = case.startNanos
            val deadline = createDeadline(case.waitTime) { tickerNanos }

            tickerNanos += case.elapsedNanos

            deadline.remainingNanos() shouldBeEqualTo case.expectedRemainingNanos
            deadline.remainingMillisForDelay(50L) shouldBeEqualTo case.expectedDelayMillis
            deadline.hasTimeRemaining() shouldBeEqualTo case.hasTimeRemaining
        }
    }

    @Test
    fun `ticker wrap preserves the complete wait budget`() {
        var tickerNanos = Long.MAX_VALUE - 10L
        val timeout = 100.milliseconds
        val deadline = createDeadline(timeout) { tickerNanos }

        deadline.remainingNanos() shouldBeEqualTo timeout.inWholeNanoseconds
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 50L

        tickerNanos += 11L

        deadline.remainingNanos() shouldBeEqualTo timeout.inWholeNanoseconds - 11L
        deadline.remainingMillisForDelay(50L) shouldBeEqualTo 50L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += timeout.inWholeNanoseconds - 11L

        deadline.remainingNanos() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    protected interface DeadlineProbe {
        fun remainingNanos(): Long
        fun remainingMillisForDelay(maxDelayMillis: Long): Long
        fun hasTimeRemaining(): Boolean
    }

    private data class DeadlineCase(
        val waitTime: Duration,
        val startNanos: Long,
        val elapsedNanos: Long,
        val expectedRemainingNanos: Long,
        val expectedDelayMillis: Long,
        val hasTimeRemaining: Boolean,
    )

    companion object {
        private val deadlineCases = listOf(
            DeadlineCase(0.milliseconds, 42L, 0L, 0L, 0L, false),
            DeadlineCase((-1).milliseconds, 42L, 0L, 0L, 0L, false),
            DeadlineCase(1.milliseconds, 1_000_000L, 999_500L, 500L, 1L, true),
        )
    }
}

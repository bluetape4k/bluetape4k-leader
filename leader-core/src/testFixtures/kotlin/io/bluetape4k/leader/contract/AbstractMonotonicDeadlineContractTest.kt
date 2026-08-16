package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.stream.Stream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * JDBC와 R2DBC가 공유하는 monotonic deadline 및 lock wait 결과 계약입니다.
 *
 * 각 모듈은 내부 deadline helper와 실제 단일·그룹 lock을 adapter로 제공해
 * 동일한 경계값과 결과표를 실행합니다.
 */
abstract class AbstractMonotonicDeadlineContractTest {

    protected abstract fun createDeadline(
        waitTime: Duration,
        ticker: () -> Long,
    ): DeadlineProbe

    protected abstract fun observeWaitOutcome(case: WaitOutcomeCase): WaitOutcome

    @Test
    fun `wall clock 보정은 monotonic wait budget을 변경하지 않는다`() {
        val time = MutableClockTicker(
            tickerNanos = 1_000_000_000L,
            wallInstant = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val deadline = createDeadline(100.milliseconds, time::readTicker)

        time.jumpWallSeconds(3_600L)
        time.instant() shouldBeEqualTo Instant.parse("2026-01-01T01:00:00Z")
        deadline.remainingMillisForSleep() shouldBeEqualTo 100L

        time.jumpWallSeconds(-7_200L)
        time.instant() shouldBeEqualTo Instant.parse("2025-12-31T23:00:00Z")
        deadline.remainingMillisForSleep() shouldBeEqualTo 100L

        time.advanceTicker(40.milliseconds)
        deadline.remainingMillisForSleep() shouldBeEqualTo 60L
        deadline.hasTimeRemaining().shouldBeTrue()

        time.advanceTicker(60.milliseconds)
        deadline.remainingMillisForSleep() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @Test
    fun `zero negative sub millisecond 경계를 동일한 표로 검증한다`() {
        deadlineCases.forEach { case ->
            var tickerNanos = case.startNanos
            val deadline = createDeadline(case.waitTime) { tickerNanos }

            tickerNanos += case.elapsedNanos

            deadline.remainingNanos() shouldBeEqualTo case.expectedRemainingNanos
            deadline.remainingMillisForSleep() shouldBeEqualTo case.expectedSleepMillis
            deadline.hasTimeRemaining() shouldBeEqualTo case.hasTimeRemaining
        }
    }

    @Test
    fun `ticker wrap 경계에서도 전체 wait budget을 보존한다`() {
        var tickerNanos = Long.MAX_VALUE - 10L
        val timeout = 100.milliseconds
        val deadline = createDeadline(timeout) { tickerNanos }

        deadline.remainingNanos() shouldBeEqualTo timeout.inWholeNanoseconds

        tickerNanos += 11L
        deadline.remainingNanos() shouldBeEqualTo timeout.inWholeNanoseconds - 11L
        deadline.hasTimeRemaining().shouldBeTrue()

        tickerNanos += timeout.inWholeNanoseconds - 11L
        deadline.remainingNanos() shouldBeEqualTo 0L
        deadline.hasTimeRemaining().shouldBeFalse()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.bluetape4k.leader.contract.AbstractMonotonicDeadlineContractTest#waitOutcomeCases")
    fun `single group wait 결과는 동일한 runtime 표를 따른다`(case: WaitOutcomeCase) {
        observeWaitOutcome(case) shouldBeEqualTo case.expected
    }

    protected interface DeadlineProbe {
        fun remainingNanos(): Long
        fun remainingMillisForSleep(): Long
        fun hasTimeRemaining(): Boolean
    }

    data class WaitOutcomeCase(
        val target: LockTarget,
        val boundary: WaitBoundary,
        val waitTime: Duration,
        val expected: WaitOutcome,
    ) {
        override fun toString(): String = "$target-$boundary-$expected"
    }

    enum class LockTarget {
        SINGLE,
        GROUP,
    }

    enum class WaitBoundary {
        ZERO,
        NEGATIVE,
        TIMEOUT,
        CANCELLATION,
    }

    enum class WaitOutcome {
        SKIPPED,
        CANCELLED,
    }

    private data class DeadlineCase(
        val waitTime: Duration,
        val startNanos: Long,
        val elapsedNanos: Long,
        val expectedRemainingNanos: Long,
        val expectedSleepMillis: Long,
        val hasTimeRemaining: Boolean,
    )

    private class MutableClockTicker(
        private var tickerNanos: Long,
        private var wallInstant: Instant,
        private val zoneId: ZoneId = ZoneOffset.UTC,
    ): Clock() {

        fun readTicker(): Long = tickerNanos

        fun advanceTicker(duration: Duration) {
            tickerNanos += duration.inWholeNanoseconds
        }

        fun jumpWallSeconds(seconds: Long) {
            wallInstant = wallInstant.plusSeconds(seconds)
        }

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClockTicker(tickerNanos, wallInstant, zone)

        override fun instant(): Instant = wallInstant
    }

    companion object {
        @JvmStatic
        fun waitOutcomeCases(): Stream<WaitOutcomeCase> = LockTarget.entries
            .flatMap { target ->
                listOf(
                    WaitOutcomeCase(target, WaitBoundary.ZERO, Duration.ZERO, WaitOutcome.SKIPPED),
                    WaitOutcomeCase(target, WaitBoundary.NEGATIVE, (-1).milliseconds, WaitOutcome.SKIPPED),
                    WaitOutcomeCase(target, WaitBoundary.TIMEOUT, 25.milliseconds, WaitOutcome.SKIPPED),
                    WaitOutcomeCase(target, WaitBoundary.CANCELLATION, 1.seconds, WaitOutcome.CANCELLED),
                )
            }
            .stream()

        private val deadlineCases = listOf(
            DeadlineCase(0.milliseconds, 42L, 0L, 0L, 0L, false),
            DeadlineCase((-1).milliseconds, 42L, 0L, 0L, 0L, false),
            DeadlineCase(1.milliseconds, 1_000_000L, 999_500L, 500L, 1L, true),
            DeadlineCase(
                waitTime = 100.milliseconds,
                startNanos = -5_000_000_000L,
                elapsedNanos = 40.milliseconds.inWholeNanoseconds,
                expectedRemainingNanos = 60.milliseconds.inWholeNanoseconds,
                expectedSleepMillis = 60L,
                hasTimeRemaining = true,
            ),
        )
    }
}

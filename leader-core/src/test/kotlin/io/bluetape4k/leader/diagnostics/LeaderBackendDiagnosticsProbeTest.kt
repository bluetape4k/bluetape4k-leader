package io.bluetape4k.leader.diagnostics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaderBackendDiagnosticsProbeTest {

    private val checkedAt = Instant.parse("2026-08-24T00:00:00Z")
    private val clock = Clock.fixed(checkedAt, ZoneOffset.UTC)

    @Test
    fun `UP DOWN UNKNOWN은 동일한 checkedAt으로 매핑된다`() {
        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.UP
        }.shouldBeEqualTo(LeaderBackendConnectivity.up(checkedAt))

        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.DOWN
        }.shouldBeEqualTo(LeaderBackendConnectivity.down(checkedAt))

        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.UNKNOWN
        }.shouldBeEqualTo(LeaderBackendConnectivity.unknown(checkedAt))
    }

    @Test
    fun `양수 유한 timeout이 아니면 clock과 callback을 호출하지 않는다`() {
        var clockCalls = 0
        var callbackCalls = 0
        val countingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant {
                clockCalls++
                return checkedAt
            }
        }

        listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach { timeout ->
            assertFailsWith<IllegalArgumentException> {
                LeaderBackendDiagnosticsProbe.check(timeout, countingClock) {
                    callbackCalls++
                    LeaderBackendConnectivityStatus.UP
                }
            }
        }

        clockCalls shouldBeEqualTo 0
        callbackCalls shouldBeEqualTo 0
    }

    @Test
    fun `일반 Exception은 UNKNOWN으로 정규화한다`() {
        val connectivity = LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            throw IllegalStateException("provider failure")
        }

        connectivity shouldBeEqualTo LeaderBackendConnectivity.unknown(
            checkedAt,
            reason = LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
        )
    }

    @Test
    fun `UNKNOWN callback은 caller가 지정한 bounded reason으로 기록한다`() {
        val connectivity = LeaderBackendDiagnosticsProbe.check(
            timeout = 100.milliseconds,
            clock = clock,
            unknownReason = LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED,
        ) {
            LeaderBackendConnectivityStatus.UNKNOWN
        }

        connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED
    }

    @Test
    fun `unknownReason은 UNKNOWN 전용 reason만 허용한다`() {
        listOf(
            LeaderBackendConnectivityReason.NOT_CHECKED,
            LeaderBackendConnectivityReason.CONNECTED,
            LeaderBackendConnectivityReason.DISCONNECTED,
        ).forEach { invalidReason ->
            assertFailsWith<IllegalArgumentException> {
                LeaderBackendDiagnosticsProbe.check(
                    timeout = 100.milliseconds,
                    clock = clock,
                    unknownReason = invalidReason,
                ) {
                    LeaderBackendConnectivityStatus.UNKNOWN
                }
            }
        }
    }

    @Test
    fun `CancellationException은 동일 인스턴스로 재전파한다`() {
        val cancellation = CancellationException("cancelled")

        val thrown = assertFailsWith<CancellationException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw cancellation }
        }

        thrown shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `InterruptedException은 flag를 복원하고 동일 인스턴스로 재전파한다`() {
        Thread.interrupted()
        val interrupted = InterruptedException("interrupted")
        try {
            val thrown = assertFailsWith<InterruptedException> {
                LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw interrupted }
            }

            thrown shouldBeSameInstanceAs interrupted
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `Error는 동일 인스턴스로 재전파한다`() {
        val fatal = AssertionError("fatal")

        val thrown = assertFailsWith<AssertionError> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw fatal }
        }

        thrown shouldBeSameInstanceAs fatal
    }

    @Test
    fun `NOT_CHECKED callback 결과는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
                LeaderBackendConnectivityStatus.NOT_CHECKED
            }
        }
    }

    @Test
    fun `clock은 callback보다 먼저 한 번 읽고 callback은 같은 timeout으로 한 번 실행한다`() {
        val events = mutableListOf<String>()
        var capturedTimeout: Duration? = null
        val callerThread = Thread.currentThread()
        val orderedClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant {
                events += "clock"
                return checkedAt
            }
        }

        LeaderBackendDiagnosticsProbe.check(275.milliseconds, orderedClock) { timeout ->
            Thread.currentThread() shouldBeSameInstanceAs callerThread
            capturedTimeout = timeout
            events += "callback:$timeout"
            LeaderBackendConnectivityStatus.UP
        }

        events.first() shouldBeEqualTo "clock"
        events.size shouldBeEqualTo 2
        capturedTimeout shouldBeEqualTo 275.milliseconds
    }

    @Test
    fun `동시 호출은 helper 공유 상태 없이 각자의 timestamp와 결과를 만든다`() {
        val sequence = AtomicLong()
        val results = ConcurrentLinkedQueue<LeaderBackendConnectivity>()

        MultithreadingTester()
            .workers(8)
            .rounds(4)
            .add {
                val index = sequence.getAndIncrement()
                val expectedAt = checkedAt.plusMillis(index)
                val expectedStatus = if (index % 2L == 0L) {
                    LeaderBackendConnectivityStatus.UP
                } else {
                    LeaderBackendConnectivityStatus.DOWN
                }
                val actual = LeaderBackendDiagnosticsProbe.check(
                    (index + 1L).milliseconds,
                    Clock.fixed(expectedAt, ZoneOffset.UTC),
                ) { expectedStatus }

                actual shouldBeEqualTo when (expectedStatus) {
                    LeaderBackendConnectivityStatus.UP -> LeaderBackendConnectivity.up(expectedAt)
                    LeaderBackendConnectivityStatus.DOWN -> LeaderBackendConnectivity.down(expectedAt)
                    else -> error("unexpected test status: $expectedStatus")
                }
                results.add(actual)
            }
            .run()

        results.size shouldBeEqualTo 32
        results.toSet().size shouldBeEqualTo 32
    }

    @Test
    fun `clock 실패는 callback 없이 동일 인스턴스로 전파한다`() {
        val failure = IllegalStateException("clock failure")
        val failingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant = throw failure
        }
        var callbackCalls = 0

        val thrown = assertFailsWith<IllegalStateException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, failingClock) {
                callbackCalls++
                LeaderBackendConnectivityStatus.UP
            }
        }

        thrown shouldBeSameInstanceAs failure
        callbackCalls shouldBeEqualTo 0
    }
}

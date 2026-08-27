package io.bluetape4k.leader.diagnostics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderBackendDiagnosticsTest {

    @Test
    fun `기본 diagnostics 조회는 connectivity probe를 실행하지 않는다`() {
        val provider = RecordingProvider()

        val diagnostics = provider.diagnostics()

        diagnostics.descriptor shouldBeEqualTo descriptor
        diagnostics.connectivity shouldBeEqualTo LeaderBackendConnectivity.notChecked()
        provider.calls shouldBeEqualTo 0
    }

    @Test
    fun `명시적 diagnostics 조회는 검증한 timeout으로 probe를 한 번 실행한다`() {
        val provider = RecordingProvider()

        val diagnostics = provider.diagnostics(probe = true, timeout = 250.milliseconds)

        diagnostics.connectivity shouldBeEqualTo LeaderBackendConnectivity.up(checkedAt, 7L)
        provider.calls shouldBeEqualTo 1
        provider.lastTimeout shouldBeEqualTo 250.milliseconds
    }

    @Test
    fun `probe timeout은 양수이고 유한해야 한다`() {
        val provider = RecordingProvider()

        listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach { timeout ->
            assertFailsWith<IllegalArgumentException> {
                provider.diagnostics(probe = true, timeout = timeout)
            }
        }

        provider.calls shouldBeEqualTo 0
    }

    @Test
    fun `backend descriptor는 식별자와 표시 이름을 검증한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDescriptor(" ", "Test", capabilities)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDescriptor("test", " ", capabilities)
        }
    }

    @Test
    fun `backend limitations는 공백과 중복을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            capabilities.copy(limitations = listOf(" "))
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities.copy(limitations = listOf("bounded passive check", "bounded passive check"))
        }
    }

    @Test
    fun `connectivity는 status별 timestamp와 latency 불변식을 지킨다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendConnectivity(
                status = LeaderBackendConnectivityStatus.NOT_CHECKED,
                checkedAt = checkedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendConnectivity(status = LeaderBackendConnectivityStatus.UP)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendConnectivity.up(checkedAt, latencyMillis = -1L)
        }
    }

    @Test
    fun `connectivity factory는 status별 bounded reason을 제공한다`() {
        LeaderBackendConnectivity.notChecked().reason shouldBeEqualTo
                LeaderBackendConnectivityReason.NOT_CHECKED
        LeaderBackendConnectivity.up(checkedAt).reason shouldBeEqualTo
                LeaderBackendConnectivityReason.CONNECTED
        LeaderBackendConnectivity.down(checkedAt).reason shouldBeEqualTo
                LeaderBackendConnectivityReason.DISCONNECTED
        LeaderBackendConnectivity.unknown(checkedAt).reason shouldBeEqualTo
                LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED

        LeaderBackendConnectivity.unknown(
            checkedAt = checkedAt,
            reason = LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED,
        ).reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED

        val preserved = LeaderBackendConnectivity.unknown(
            checkedAt = checkedAt,
            reason = LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
        )
        preserved.copy(status = LeaderBackendConnectivityStatus.UNKNOWN).reason shouldBeEqualTo
                LeaderBackendConnectivityReason.PROVIDER_EXCEPTION
    }

    @Test
    fun `connectivity는 NOT_CHECKED reason과 checked status의 reason 혼용을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendConnectivity(
                status = LeaderBackendConnectivityStatus.UP,
                checkedAt = checkedAt,
                reason = LeaderBackendConnectivityReason.NOT_CHECKED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendConnectivity(
                status = LeaderBackendConnectivityStatus.NOT_CHECKED,
                reason = LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
            )
        }
    }

    @Test
    fun `default connectivity check는 안전한 UNKNOWN 결과를 반환한다`() {
        val provider = object : LeaderBackendDiagnosticsProvider {
            override val backendDescriptor: LeaderBackendDescriptor = descriptor
        }

        val connectivity = provider.checkConnectivity(100.milliseconds)

        connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED
    }

    @Test
    fun `legacy checkConnectivity override는 diagnostics 선검증과 custom 예외 경계를 유지한다`() {
        val provider = LegacyCheckConnectivityProvider()

        assertFailsWith<IllegalArgumentException> {
            provider.diagnostics(probe = true, timeout = Duration.ZERO)
        }
        provider.calls shouldBeEqualTo 0

        provider.behavior = { LeaderBackendConnectivity.notChecked() }
        provider.checkConnectivity(100.milliseconds) shouldBeEqualTo LeaderBackendConnectivity.notChecked()

        val ordinary = IllegalStateException("legacy failure")
        provider.behavior = { throw ordinary }
        assertFailsWith<IllegalStateException> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(ordinary)

        val cancellation = CancellationException("legacy cancellation")
        provider.behavior = { throw cancellation }
        assertFailsWith<CancellationException> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(cancellation)

        Thread.interrupted()
        val interrupted = InterruptedException("legacy interruption")
        provider.behavior = { throw interrupted }
        try {
            assertFailsWith<InterruptedException> {
                provider.diagnostics(probe = true, timeout = 100.milliseconds)
            }.shouldBeSameInstanceAs(interrupted)
        } finally {
            Thread.interrupted()
        }

        val fatal = AssertionError("legacy fatal")
        provider.behavior = { throw fatal }
        assertFailsWith<AssertionError> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(fatal)
    }

    @Test
    fun `legacy diagnostics override는 base prevalidation과 helper를 우회하는 계약을 유지한다`() {
        val provider = LegacyDiagnosticsOverrideProvider()
        val expected = LeaderBackendDiagnostics(descriptor, LeaderBackendConnectivity.notChecked())

        provider.diagnostics(probe = true, timeout = Duration.ZERO) shouldBeEqualTo expected
        provider.calls shouldBeEqualTo 1
        provider.lastTimeout shouldBeEqualTo Duration.ZERO

        val ordinary = IllegalStateException("custom diagnostics failure")
        provider.behavior = { throw ordinary }
        assertFailsWith<IllegalStateException> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(ordinary)

        val cancellation = CancellationException("custom diagnostics cancellation")
        provider.behavior = { throw cancellation }
        assertFailsWith<CancellationException> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(cancellation)

        val fatal = AssertionError("custom diagnostics fatal")
        provider.behavior = { throw fatal }
        assertFailsWith<AssertionError> {
            provider.diagnostics(probe = true, timeout = 100.milliseconds)
        }.shouldBeSameInstanceAs(fatal)
    }

    private class RecordingProvider : LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = descriptor
        var calls: Int = 0
        var lastTimeout: Duration? = null

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            calls++
            lastTimeout = timeout
            return LeaderBackendConnectivity.up(checkedAt, 7L)
        }
    }

    private class LegacyCheckConnectivityProvider : LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = descriptor
        var calls: Int = 0
        var behavior: (Duration) -> LeaderBackendConnectivity = {
            calls++
            LeaderBackendConnectivity.notChecked()
        }

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            calls++
            return behavior(timeout)
        }
    }

    private class LegacyDiagnosticsOverrideProvider : LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = descriptor
        var calls: Int = 0
        var lastTimeout: Duration? = null
        var behavior: (Duration) -> LeaderBackendDiagnostics = { timeout ->
            lastTimeout = timeout
            LeaderBackendDiagnostics(descriptor, LeaderBackendConnectivity.notChecked())
        }

        override fun diagnostics(probe: Boolean, timeout: Duration): LeaderBackendDiagnostics {
            calls++
            return behavior(timeout)
        }
    }

    private companion object {
        val checkedAt: Instant = Instant.parse("2026-08-16T00:00:00Z")
        val capabilities = LeaderBackendCapabilities(
            singleExecutionModels = setOf(LeaderExecutionModel.BLOCKING),
            groupExecutionModels = emptySet(),
            leaseExtension = LeaderBackendModeSupport(
                single = LeaderBackendSupport.SUPPORTED,
                group = LeaderBackendSupport.UNSUPPORTED,
            ),
            auditState = LeaderBackendModeSupport(
                single = LeaderBackendSupport.UNKNOWN,
                group = LeaderBackendSupport.UNSUPPORTED,
            ),
            clockSource = LeaderBackendClockSource.PROCESS,
            ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
        )
        val descriptor = LeaderBackendDescriptor(
            backendId = "test",
            displayName = "Test Backend",
            capabilities = capabilities,
        )
    }
}

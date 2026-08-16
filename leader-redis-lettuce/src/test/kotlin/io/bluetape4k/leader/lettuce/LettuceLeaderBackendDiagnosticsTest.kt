package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceLeaderBackendDiagnosticsTest {

    @Test
    fun `Lettuce descriptor는 native single group 실행 모델과 lease 지원을 보고한다`() {
        val descriptor = LettuceLeaderBackendDiagnostics(mockk()).backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "redis-lettuce"
        descriptor.displayName shouldBeEqualTo "Redis Lettuce"
        capabilities.singleExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.groupExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.BACKEND
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SERVER_TTL
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `Lettuce connectivity는 기존 connection open 상태만 확인한다`() {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.isOpen } returns true
        val provider = LettuceLeaderBackendDiagnostics(connection)

        val up = provider.checkConnectivity(100.milliseconds)
        up.status shouldBeEqualTo LeaderBackendConnectivityStatus.UP

        every { connection.isOpen } returns false
        val down = provider.checkConnectivity(100.milliseconds)
        down.status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
    }

    @Test
    fun `모든 canonical Lettuce elector는 동일한 diagnostics descriptor를 제공한다`() {
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        val expected = LettuceLeaderBackendDiagnostics(connection).backendDescriptor
        val electors = listOf(
            LettuceLeaderElector(connection, LeaderElectionOptions.Default),
            LettuceLeaderGroupElector(connection, LeaderGroupElectionOptions.Default),
            LettuceSuspendLeaderElector(connection, LeaderElectionOptions.Default),
            LettuceSuspendLeaderGroupElector(connection, LeaderGroupElectionOptions.Default),
        )

        electors.forEach { elector ->
            elector.backendDescriptor shouldBeEqualTo expected
        }
    }

    private companion object {
        val nativeExecutionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val unsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }
}

package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import kotlin.time.Duration.Companion.milliseconds

class RedissonLeaderBackendDiagnosticsTest {

    @Test
    fun `Redisson descriptor는 native single group 실행 모델과 lease 지원을 보고한다`() {
        val descriptor = RedissonLeaderBackendDiagnostics(mockk()).backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "redis-redisson"
        descriptor.displayName shouldBeEqualTo "Redis Redisson"
        capabilities.singleExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.groupExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.BACKEND
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SERVER_TTL
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `Redisson connectivity는 기존 client shutdown 상태만 확인한다`() {
        val client = mockk<RedissonClient>()
        every { client.isShutdown } returns false
        every { client.isShuttingDown } returns false
        val provider = RedissonLeaderBackendDiagnostics(client)

        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.UP

        every { client.isShuttingDown } returns true
        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN

        every { client.isShuttingDown } returns false
        every { client.isShutdown } returns true
        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
    }

    @Test
    fun `모든 canonical Redisson elector는 동일한 diagnostics descriptor를 제공한다`() {
        val client = mockk<RedissonClient>(relaxed = true)
        val expected = RedissonLeaderBackendDiagnostics(client).backendDescriptor
        val electors = listOf(
            RedissonLeaderElector(client, LeaderElectionOptions.Default),
            RedissonLeaderGroupElector(client, LeaderGroupElectionOptions.Default),
            RedissonSuspendLeaderElector(client, LeaderElectionOptions.Default),
            RedissonSuspendLeaderGroupElector(client, LeaderGroupElectionOptions.Default),
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

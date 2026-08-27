package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import java.util.concurrent.CancellationException
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
    fun `Redisson lifecycle 상태는 backend 연결 성공으로 승격하지 않는다`() {
        val client = mockk<RedissonClient>()
        every { client.isShutdown } returns false
        every { client.isShuttingDown } returns false
        val provider = RedissonLeaderBackendDiagnostics(client)

        val unknown = provider.checkConnectivity(100.milliseconds)
        unknown.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        unknown.reason shouldBeEqualTo LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED

        every { client.isShuttingDown } returns true
        val shuttingDown = provider.checkConnectivity(100.milliseconds)
        shuttingDown.status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
        shuttingDown.reason shouldBeEqualTo LeaderBackendConnectivityReason.DISCONNECTED

        every { client.isShuttingDown } returns false
        every { client.isShutdown } returns true
        val shutdown = provider.checkConnectivity(100.milliseconds)
        shutdown.status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
        shutdown.reason shouldBeEqualTo LeaderBackendConnectivityReason.DISCONNECTED
    }

    @Test
    fun `Redisson client Exception은 UNKNOWN으로 정규화한다`() {
        val client = mockk<RedissonClient>()
        every { client.isShutdown } throws IllegalStateException("probe failed")

        val connectivity = RedissonLeaderBackendDiagnostics(client)
            .checkConnectivity(100.milliseconds)

        connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_EXCEPTION
    }

    @Test
    fun `Redisson client CancellationException은 동일 인스턴스로 재전파한다`() {
        val cancellation = CancellationException("probe cancelled")
        val client = mockk<RedissonClient>()
        every { client.isShutdown } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            RedissonLeaderBackendDiagnostics(client).checkConnectivity(100.milliseconds)
        }

        thrown shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `Redisson client InterruptedException은 flag를 복원하고 동일 인스턴스로 재전파한다`() {
        Thread.interrupted()
        val interrupted = InterruptedException("probe interrupted")
        val client = mockk<RedissonClient>()
        every { client.isShutdown } throws interrupted

        try {
            val thrown = assertFailsWith<InterruptedException> {
                RedissonLeaderBackendDiagnostics(client).checkConnectivity(100.milliseconds)
            }

            thrown shouldBeSameInstanceAs interrupted
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `Redisson client Error는 동일 인스턴스로 재전파한다`() {
        val fatal = AssertionError("fatal Redisson probe")
        val client = mockk<RedissonClient>()
        every { client.isShutdown } throws fatal

        val thrown = assertFailsWith<AssertionError> {
            RedissonLeaderBackendDiagnostics(client).checkConnectivity(100.milliseconds)
        }

        thrown shouldBeSameInstanceAs fatal
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

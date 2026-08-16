package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.LifecycleService
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class HazelcastLeaderBackendDiagnosticsTest {

    @Test
    fun `descriptor는 Hazelcast 실행 모델과 server TTL 계약을 보고한다`() {
        val provider = HazelcastLeaderBackendDiagnostics(mockk(relaxed = true))
        val descriptor = provider.backendDescriptor
        val capabilities = descriptor.capabilities
        val executionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )

        descriptor.backendId shouldBeEqualTo "hazelcast"
        descriptor.displayName shouldBeEqualTo "Hazelcast"
        capabilities.singleExecutionModels shouldBeEqualTo executionModels
        capabilities.groupExecutionModels shouldBeEqualTo executionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.NOT_APPLICABLE
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SERVER_TTL
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `lifecycle 상태는 backend 연결 성공으로 승격하지 않는다`() {
        val lifecycle = mockk<LifecycleService>()
        val hazelcast = mockk<HazelcastInstance>()
        every { hazelcast.lifecycleService } returns lifecycle
        every { lifecycle.isRunning } returnsMany listOf(true, false)
        val provider = HazelcastLeaderBackendDiagnostics(hazelcast)

        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
    }

    @Test
    fun `canonical Hazelcast elector는 diagnostics provider를 구현한다`() {
        canonicalElectors.forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java.isAssignableFrom(electorType) shouldBe true
        }
    }

    private companion object {
        val canonicalElectors = listOf(
            HazelcastLeaderElector::class.java,
            HazelcastLeaderGroupElector::class.java,
            HazelcastSuspendLeaderElector::class.java,
            HazelcastSuspendLeaderGroupElector::class.java,
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

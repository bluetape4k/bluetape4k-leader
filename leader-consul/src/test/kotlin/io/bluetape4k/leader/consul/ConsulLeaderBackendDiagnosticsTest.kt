package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ConsulLeaderBackendDiagnosticsTest {

    @Test
    fun `descriptor는 Consul 실행 모델과 session 계약을 보고한다`() {
        val descriptor = ConsulLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "consul"
        descriptor.displayName shouldBeEqualTo "Consul"
        capabilities.singleExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.groupExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo auditModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.BACKEND
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SESSION
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 lock client 호출 없이 UNKNOWN을 반환한다`() {
        ConsulLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `모든 canonical Consul elector는 동일한 diagnostics provider를 구현한다`() {
        listOf(
            ConsulLeaderElector::class.java,
            ConsulLeaderGroupElector::class.java,
            ConsulSuspendLeaderElector::class.java,
            ConsulSuspendLeaderGroupElector::class.java,
        ).forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java
                .isAssignableFrom(electorType) shouldBe true
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
        val auditModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }
}

package io.bluetape4k.leader.k8s

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

class KubernetesLeaderBackendDiagnosticsTest {

    @Test
    fun `descriptor는 Kubernetes 실행 모델과 lease 계약을 보고한다`() {
        val descriptor = KubernetesLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities
        val executionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )

        descriptor.backendId shouldBeEqualTo "kubernetes"
        descriptor.displayName shouldBeEqualTo "Kubernetes Lease"
        capabilities.singleExecutionModels shouldBeEqualTo executionModels
        capabilities.groupExecutionModels shouldBeEqualTo executionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo singleAuditModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.PROCESS
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.CLIENT_LEASE
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 안전한 lifecycle 상태가 없으면 UNKNOWN을 반환한다`() {
        KubernetesLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `canonical Kubernetes elector는 diagnostics provider를 구현한다`() {
        canonicalElectors.forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java.isAssignableFrom(electorType) shouldBe true
        }
    }

    private companion object {
        val canonicalElectors = listOf(
            KubernetesLeaseLeaderElector::class.java,
            KubernetesLeaseLeaderGroupElector::class.java,
            KubernetesLeaseSuspendLeaderElector::class.java,
            KubernetesLeaseSuspendLeaderGroupElector::class.java,
        )
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val singleAuditModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }
}

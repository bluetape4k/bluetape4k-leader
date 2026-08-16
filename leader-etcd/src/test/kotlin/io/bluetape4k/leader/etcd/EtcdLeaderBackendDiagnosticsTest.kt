package io.bluetape4k.leader.etcd

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

class EtcdLeaderBackendDiagnosticsTest {

    @Test
    fun `descriptor는 etcd 실행 모델과 backend lease 계약을 보고한다`() {
        val descriptor = EtcdLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "etcd"
        descriptor.displayName shouldBeEqualTo "etcd"
        capabilities.singleExecutionModels shouldBeEqualTo setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
            LeaderExecutionModel.VIRTUAL_THREAD,
        )
        capabilities.groupExecutionModels shouldBeEqualTo setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo auditModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.BACKEND
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SERVER_TTL
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 client 호출 없이 UNKNOWN을 반환한다`() {
        EtcdLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `모든 canonical etcd elector는 동일한 diagnostics provider를 구현한다`() {
        listOf(
            EtcdLeaderElector::class.java,
            EtcdLeaderGroupElector::class.java,
            EtcdSuspendLeaderElector::class.java,
            EtcdSuspendLeaderGroupElector::class.java,
            EtcdVirtualThreadLeaderElector::class.java,
        ).forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java
                .isAssignableFrom(electorType) shouldBe true
        }
    }

    private companion object {
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val auditModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
        val unsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }
}

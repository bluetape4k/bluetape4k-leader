package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ConsulLeaderBackendDiagnosticsTest {

    private companion object: KLogging() {
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
        capabilities.limitations.shouldBeEmpty()
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
                .isAssignableFrom(electorType).shouldBeTrue()
        }
    }
}

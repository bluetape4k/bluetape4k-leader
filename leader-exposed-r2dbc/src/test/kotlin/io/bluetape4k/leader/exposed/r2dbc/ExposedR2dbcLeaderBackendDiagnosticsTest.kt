package io.bluetape4k.leader.exposed.r2dbc

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

class ExposedR2dbcLeaderBackendDiagnosticsTest {

    @Test
    fun `descriptor는 R2DBC suspend 모델과 DB clock 계약을 보고한다`() {
        val descriptor = ExposedR2dbcLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "exposed-r2dbc"
        descriptor.displayName shouldBeEqualTo "Exposed R2DBC"
        capabilities.singleExecutionModels shouldBeEqualTo setOf(LeaderExecutionModel.SUSPEND)
        capabilities.groupExecutionModels shouldBeEqualTo setOf(LeaderExecutionModel.SUSPEND)
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.CONFIGURABLE
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.DATABASE_TIMESTAMP
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 외부 I O 없이 UNKNOWN을 반환한다`() {
        ExposedR2dbcLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `canonical R2DBC elector는 diagnostics provider를 구현한다`() {
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedR2DbcSuspendLeaderElector::class.java) shouldBe true
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedR2DbcSuspendLeaderGroupElector::class.java) shouldBe true
    }

    private companion object {
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

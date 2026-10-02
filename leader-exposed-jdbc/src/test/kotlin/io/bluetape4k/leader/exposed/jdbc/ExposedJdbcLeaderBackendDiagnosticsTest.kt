package io.bluetape4k.leader.exposed.jdbc

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
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ExposedJdbcLeaderBackendDiagnosticsTest {

    private companion object: KLogging() {
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val unsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }

    @Test
    fun `descriptor는 JDBC 실행 모델과 DB clock 계약을 보고한다`() {
        val descriptor = ExposedJdbcLeaderBackendDiagnostics.backendDescriptor
        log.debug { "descriptor=$descriptor" }

        val capabilities = descriptor.capabilities
        log.debug { "capabilities=$capabilities" }

        descriptor.backendId shouldBeEqualTo "exposed-jdbc"
        descriptor.displayName shouldBeEqualTo "Exposed JDBC"

        capabilities.singleExecutionModels shouldBeEqualTo setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.VIRTUAL_THREAD,
        )
        capabilities.groupExecutionModels shouldBeEqualTo setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.VIRTUAL_THREAD,
        )
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.CONFIGURABLE
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.DATABASE_TIMESTAMP
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 외부 I O 없이 UNKNOWN을 반환한다`() {
        ExposedJdbcLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `canonical JDBC elector는 diagnostics provider를 구현한다`() {
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedJdbcLeaderElector::class.java).shouldBeTrue()
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedJdbcLeaderGroupElector::class.java).shouldBeTrue()
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedJdbcVirtualThreadLeaderElector::class.java).shouldBeTrue()
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(ExposedJdbcVirtualThreadLeaderGroupElector::class.java).shouldBeTrue()
    }
}

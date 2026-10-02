package io.bluetape4k.leader.dynamodb

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
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DynamoDbLeaderBackendDiagnosticsTest {

    private companion object: KLogging() {
        val canonicalElectors = listOf(
            DynamoDbLeaderElector::class.java,
            DynamoDbLeaderGroupElector::class.java,
            DynamoDbSuspendLeaderElector::class.java,
            DynamoDbSuspendLeaderGroupElector::class.java,
            DynamoDbVirtualThreadLeaderElector::class.java,
            DynamoDbVirtualThreadLeaderGroupElector::class.java,
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

    @Test
    fun `descriptor는 DynamoDB 실행 모델과 lease 계약을 보고한다`() {
        val descriptor = DynamoDbLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        log.debug { "descriptor=$descriptor" }
        descriptor.backendId shouldBeEqualTo "dynamodb"
        descriptor.displayName shouldBeEqualTo "DynamoDB"

        log.debug { "capabilities=$capabilities" }
        capabilities.singleExecutionModels shouldBeEqualTo LeaderExecutionModel.entries.toSet()
        capabilities.groupExecutionModels shouldBeEqualTo LeaderExecutionModel.entries.toSet()
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo singleAuditModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.PROCESS
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.CLIENT_LEASE
        capabilities.limitations.shouldBeEmpty()
    }

    @Test
    fun `connectivity는 외부 I O 없이 UNKNOWN을 반환한다`() {
        DynamoDbLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `canonical DynamoDB elector는 diagnostics provider를 구현한다`() {
        canonicalElectors.forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java.isAssignableFrom(electorType).shouldBeTrue()
        }
    }
}

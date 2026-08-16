package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** DynamoDB backend의 정적 capability와 안전한 connectivity 계약입니다. */
object DynamoDbLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val SingleAuditModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "dynamodb",
        displayName = "DynamoDB",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = LeaderExecutionModel.entries.toSet(),
            groupExecutionModels = LeaderExecutionModel.entries.toSet(),
            leaseExtension = SupportedModes,
            auditState = SingleAuditModes,
            clockSource = LeaderBackendClockSource.PROCESS,
            ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
        ),
    )
}

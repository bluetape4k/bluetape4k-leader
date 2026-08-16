package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** Exposed JDBC backend의 정적 capability와 안전한 connectivity 계약입니다. */
object ExposedJdbcLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val UnsupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.UNSUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "exposed-jdbc",
        displayName = "Exposed JDBC",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = setOf(
                LeaderExecutionModel.BLOCKING,
                LeaderExecutionModel.ASYNC,
                LeaderExecutionModel.VIRTUAL_THREAD,
            ),
            groupExecutionModels = setOf(
                LeaderExecutionModel.BLOCKING,
                LeaderExecutionModel.ASYNC,
            ),
            leaseExtension = SupportedModes,
            auditState = UnsupportedModes,
            clockSource = LeaderBackendClockSource.CONFIGURABLE,
            ttlMode = LeaderBackendTtlMode.DATABASE_TIMESTAMP,
        ),
    )
}

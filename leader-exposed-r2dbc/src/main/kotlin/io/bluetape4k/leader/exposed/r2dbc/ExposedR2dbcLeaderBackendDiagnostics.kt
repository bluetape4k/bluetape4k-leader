package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** Exposed R2DBC backend의 정적 capability와 안전한 connectivity 계약입니다. */
object ExposedR2dbcLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val UnsupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.UNSUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "exposed-r2dbc",
        displayName = "Exposed R2DBC",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = setOf(LeaderExecutionModel.SUSPEND),
            groupExecutionModels = setOf(LeaderExecutionModel.SUSPEND),
            leaseExtension = SupportedModes,
            auditState = UnsupportedModes,
            clockSource = LeaderBackendClockSource.CONFIGURABLE,
            ttlMode = LeaderBackendTtlMode.DATABASE_TIMESTAMP,
        ),
    )
}

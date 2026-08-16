package io.bluetape4k.leader.consul

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** Consul backend의 정적 capability와 안전한 connectivity 계약입니다. */
object ConsulLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val NativeExecutionModels = setOf(
        LeaderExecutionModel.BLOCKING,
        LeaderExecutionModel.ASYNC,
        LeaderExecutionModel.SUSPEND,
    )
    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val AuditModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "consul",
        displayName = "Consul",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = NativeExecutionModels,
            groupExecutionModels = NativeExecutionModels,
            leaseExtension = SupportedModes,
            auditState = AuditModes,
            clockSource = LeaderBackendClockSource.BACKEND,
            ttlMode = LeaderBackendTtlMode.SESSION,
        ),
    )
}

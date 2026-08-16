package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** Kubernetes Lease backend의 정적 capability와 안전한 connectivity 계약입니다. */
object KubernetesLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val ExecutionModels = setOf(
        LeaderExecutionModel.BLOCKING,
        LeaderExecutionModel.ASYNC,
        LeaderExecutionModel.SUSPEND,
    )
    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val SingleAuditModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "kubernetes",
        displayName = "Kubernetes Lease",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = ExecutionModels,
            groupExecutionModels = ExecutionModels,
            leaseExtension = SupportedModes,
            auditState = SingleAuditModes,
            clockSource = LeaderBackendClockSource.PROCESS,
            ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
        ),
    )
}

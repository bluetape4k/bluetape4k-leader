package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel

/** etcd backend의 정적 capability와 안전한 connectivity 계약입니다. */
object EtcdLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val AuditModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )
    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "etcd",
        displayName = "etcd",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = setOf(
                LeaderExecutionModel.BLOCKING,
                LeaderExecutionModel.ASYNC,
                LeaderExecutionModel.SUSPEND,
                LeaderExecutionModel.VIRTUAL_THREAD,
            ),
            groupExecutionModels = setOf(
                LeaderExecutionModel.BLOCKING,
                LeaderExecutionModel.ASYNC,
                LeaderExecutionModel.SUSPEND,
            ),
            leaseExtension = SupportedModes,
            auditState = AuditModes,
            clockSource = LeaderBackendClockSource.BACKEND,
            ttlMode = LeaderBackendTtlMode.SERVER_TTL,
        ),
    )
}

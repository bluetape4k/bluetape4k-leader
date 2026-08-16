package io.bluetape4k.leader.diagnostics

import java.time.Clock
import kotlin.time.Duration

/** Process 내부 Local leader elector가 공유하는 backend diagnostics provider입니다. */
object LocalLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val AuditModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )

    override val backendDescriptor: LeaderBackendDescriptor = LeaderBackendDescriptor(
        backendId = "local",
        displayName = "Local",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = LeaderExecutionModel.entries.toSet(),
            groupExecutionModels = LeaderExecutionModel.entries.toSet(),
            leaseExtension = SupportedModes,
            auditState = AuditModes,
            clockSource = LeaderBackendClockSource.PROCESS,
            ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
        ),
    )

    /** Local elector는 외부 연결 없이 항상 사용할 수 있습니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        timeout.requirePositiveFiniteProbeTimeout()
        return LeaderBackendConnectivity.up(Clock.systemUTC().instant())
    }

}

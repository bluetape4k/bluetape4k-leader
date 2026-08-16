package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import java.time.Clock
import kotlin.time.Duration

/** MongoDB backend의 정적 capability와 안전한 connectivity 계약입니다. */
object MongoLeaderBackendDiagnostics : LeaderBackendDiagnosticsProvider {

    private val NativeExecutionModels = setOf(
        LeaderExecutionModel.BLOCKING,
        LeaderExecutionModel.ASYNC,
        LeaderExecutionModel.SUSPEND,
    )
    private val SupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.SUPPORTED,
        group = LeaderBackendSupport.SUPPORTED,
    )
    private val UnsupportedModes = LeaderBackendModeSupport(
        single = LeaderBackendSupport.UNSUPPORTED,
        group = LeaderBackendSupport.UNSUPPORTED,
    )
    private val DESCRIPTOR = LeaderBackendDescriptor(
        backendId = "mongodb",
        displayName = "MongoDB",
        capabilities = LeaderBackendCapabilities(
            singleExecutionModels = NativeExecutionModels,
            groupExecutionModels = NativeExecutionModels,
            leaseExtension = SupportedModes,
            auditState = UnsupportedModes,
            clockSource = LeaderBackendClockSource.PROCESS,
            ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
        ),
    )

    override val backendDescriptor: LeaderBackendDescriptor = DESCRIPTOR

    /** lock collection만으로 bounded 연결 성공을 증명하지 않고 UNKNOWN을 반환합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        timeout.requirePositiveFiniteProbeTimeout()
        return LeaderBackendConnectivity.unknown(Clock.systemUTC().instant())
    }
}

private fun Duration.requirePositiveFiniteProbeTimeout() {
    require(isFinite() && this > Duration.ZERO) {
        "probe timeout must be positive and finite: $this"
    }
}

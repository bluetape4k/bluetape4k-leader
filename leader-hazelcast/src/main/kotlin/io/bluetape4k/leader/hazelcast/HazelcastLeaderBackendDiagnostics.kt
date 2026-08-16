package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
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

/** 기존 Hazelcast client의 lifecycle만 읽는 backend diagnostics provider입니다. */
class HazelcastLeaderBackendDiagnostics(
    private val hazelcast: HazelcastInstance,
) : LeaderBackendDiagnosticsProvider {

    override val backendDescriptor: LeaderBackendDescriptor = Descriptor

    /** 네트워크 요청 없이 기존 client의 lifecycle running 상태를 확인합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        require(timeout.isFinite() && timeout > Duration.ZERO) {
            "probe timeout must be positive and finite: $timeout"
        }
        val checkedAt = Clock.systemUTC().instant()
        return runCatching { hazelcast.lifecycleService.isRunning }
            .fold(
                onSuccess = { running ->
                    if (running) {
                        LeaderBackendConnectivity.unknown(checkedAt)
                    } else {
                        LeaderBackendConnectivity.down(checkedAt)
                    }
                },
                onFailure = { LeaderBackendConnectivity.unknown(checkedAt) },
            )
    }

    private companion object {
        val ExecutionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )
        val SupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val UnsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
        val Descriptor = LeaderBackendDescriptor(
            backendId = "hazelcast",
            displayName = "Hazelcast",
            capabilities = LeaderBackendCapabilities(
                singleExecutionModels = ExecutionModels,
                groupExecutionModels = ExecutionModels,
                leaseExtension = SupportedModes,
                auditState = UnsupportedModes,
                clockSource = LeaderBackendClockSource.NOT_APPLICABLE,
                ttlMode = LeaderBackendTtlMode.SERVER_TTL,
            ),
        )
    }
}

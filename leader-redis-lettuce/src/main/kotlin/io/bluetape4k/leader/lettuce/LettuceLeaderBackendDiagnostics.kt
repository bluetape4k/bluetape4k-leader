package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Clock
import kotlin.time.Duration

/** Redis Lettuce backend의 정적 capability와 기존 connection 기반 connectivity 계약입니다. */
class LettuceLeaderBackendDiagnostics(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderBackendDiagnosticsProvider {

    override val backendDescriptor: LeaderBackendDescriptor = DESCRIPTOR

    /** 기존 Lettuce connection의 open 상태만 읽어 연결 상태를 확인합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        timeout.requirePositiveFiniteProbeTimeout()
        val checkedAt = Clock.systemUTC().instant()
        return if (connection.isOpen) {
            LeaderBackendConnectivity.up(checkedAt)
        } else {
            LeaderBackendConnectivity.down(checkedAt)
        }
    }

    private companion object {
        val NativeExecutionModels = setOf(
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
        val DESCRIPTOR = LeaderBackendDescriptor(
            backendId = "redis-lettuce",
            displayName = "Redis Lettuce",
            capabilities = LeaderBackendCapabilities(
                singleExecutionModels = NativeExecutionModels,
                groupExecutionModels = NativeExecutionModels,
                leaseExtension = SupportedModes,
                auditState = UnsupportedModes,
                clockSource = LeaderBackendClockSource.BACKEND,
                ttlMode = LeaderBackendTtlMode.SERVER_TTL,
            ),
        )
    }
}

private fun Duration.requirePositiveFiniteProbeTimeout() {
    require(isFinite() && this > Duration.ZERO) {
        "probe timeout must be positive and finite: $this"
    }
}

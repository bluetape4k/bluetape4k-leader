package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import org.redisson.api.RedissonClient
import java.time.Clock
import kotlin.time.Duration

/** Redis Redisson backend의 정적 capability와 기존 client 기반 connectivity 계약입니다. */
class RedissonLeaderBackendDiagnostics(
    private val redissonClient: RedissonClient,
) : LeaderBackendDiagnosticsProvider {

    override val backendDescriptor: LeaderBackendDescriptor = DESCRIPTOR

    /** 기존 Redisson client의 shutdown 상태만 읽어 연결 상태를 확인합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        timeout.requirePositiveFiniteProbeTimeout()
        val checkedAt = Clock.systemUTC().instant()
        val shutdown = redissonClient.isShutdown
        val shuttingDown = redissonClient.isShuttingDown
        return if (shutdown || shuttingDown) {
            LeaderBackendConnectivity.down(checkedAt)
        } else {
            LeaderBackendConnectivity.unknown(checkedAt)
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
            backendId = "redis-redisson",
            displayName = "Redis Redisson",
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

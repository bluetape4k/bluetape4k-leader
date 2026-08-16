package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import kotlin.time.Duration

/** 선택된 leader backend에 opt-in connectivity probe를 실행하는 health indicator입니다. */
class LeaderBackendHealthIndicator(
    private val provider: LeaderBackendDiagnosticsProvider,
    private val timeout: Duration,
) : AbstractHealthIndicator("Leader backend connectivity health check failed") {

    override fun doHealthCheck(builder: Health.Builder) {
        val diagnostics = provider.diagnostics(probe = true, timeout = timeout)
        val connectivity = diagnostics.connectivity

        when (connectivity.status) {
            LeaderBackendConnectivityStatus.UP -> builder.up()
            LeaderBackendConnectivityStatus.DOWN -> builder.down()
            LeaderBackendConnectivityStatus.UNKNOWN,
            LeaderBackendConnectivityStatus.NOT_CHECKED,
            -> builder.status(Status.UNKNOWN)
        }
        builder
            .withDetail(DETAIL_BACKEND, diagnostics.descriptor.backendId)
            .withDetail(DETAIL_CONNECTIVITY, connectivity.status.name)
        connectivity.checkedAt?.let { builder.withDetail(DETAIL_CHECKED_AT, it) }
        connectivity.latencyMillis?.let { builder.withDetail(DETAIL_LATENCY_MILLIS, it) }
    }

    private companion object {
        const val DETAIL_BACKEND = "backend"
        const val DETAIL_CONNECTIVITY = "connectivity"
        const val DETAIL_CHECKED_AT = "checkedAt"
        const val DETAIL_LATENCY_MILLIS = "latencyMillis"
    }
}

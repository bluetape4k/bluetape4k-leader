package io.bluetape4k.leader.spring.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * A [HealthIndicator] that exposes Leader AOP metrics to the Spring Boot Actuator health endpoint.
 *
 * Activated only when a `MicrometerLeaderAopMetricsRecorder` is registered.
 * Queries the `leader.aop.active` Gauge and reports the number of currently running leader tasks as a detail.
 *
 * ## Actuator Response Example
 * ```json
 * {
 *   "status": "UP",
 *   "details": {
 *     "active": 2,
 *     "trackedLocks": 3
 *   }
 * }
 * ```
 *
 * @param registry Micrometer [MeterRegistry]
 */
class LeaderMetricsHealthIndicator(
    private val registry: MeterRegistry,
) : AbstractHealthIndicator("Leader AOP metrics health check failed") {

    companion object {
        private const val METER_ACTIVE = "leader.aop.active"
        private const val DETAIL_ACTIVE = "active"
        private const val DETAIL_TRACKED_LOCKS = "trackedLocks"
    }

    override fun doHealthCheck(builder: Health.Builder) {
        val activeGauges = registry.find(METER_ACTIVE).gauges()
        val totalActive = activeGauges.sumOf { it.value().toInt() }
        val trackedLocks = activeGauges.size

        builder.up()
            .withDetail(DETAIL_ACTIVE, totalActive)
            .withDetail(DETAIL_TRACKED_LOCKS, trackedLocks)
    }
}

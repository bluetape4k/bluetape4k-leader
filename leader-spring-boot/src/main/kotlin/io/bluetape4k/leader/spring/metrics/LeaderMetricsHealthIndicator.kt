package io.bluetape4k.leader.spring.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * Leader AOP metrics 상태를 Spring Boot Actuator health endpoint에 노출하는 HealthIndicator.
 *
 * `MicrometerLeaderAopMetricsRecorder`가 등록된 경우에만 활성화된다.
 * `leader.aop.active` Gauge를 조회하여 현재 실행 중인 leader 작업 수를 detail로 표시한다.
 *
 * ## Actuator 응답 예
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

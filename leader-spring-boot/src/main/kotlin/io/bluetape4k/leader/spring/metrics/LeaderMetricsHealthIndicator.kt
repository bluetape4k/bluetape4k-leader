package io.bluetape4k.leader.spring.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * `LeaderMetricsHealthIndicator`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property registry Spring Boot integration 계약에서 사용하는 속성입니다.
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

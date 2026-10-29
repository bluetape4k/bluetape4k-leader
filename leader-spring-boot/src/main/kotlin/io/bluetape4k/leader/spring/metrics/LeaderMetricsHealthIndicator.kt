package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/** micrometer의 `leader.aop.active` gauge를 합산해 leader AOP metric 상태를 Actuator health에 제공합니다.
 *
 * @property registry active gauge를 조회할 `MeterRegistry`입니다.
 */
class LeaderMetricsHealthIndicator(
    private val registry: MeterRegistry,
): AbstractHealthIndicator("Leader AOP metrics health check failed") {

    companion object: KLogging() {
        private const val METER_ACTIVE = "leader.aop.active"
        private const val DETAIL_ACTIVE = "active"
        private const val DETAIL_TRACKED_LOCKS = "trackedLocks"
    }

    override fun doHealthCheck(builder: Health.Builder) {
        val activeGauges = registry.find(METER_ACTIVE).gauges()
        val totalActive = activeGauges.sumOf { it.value().toInt() }
        val trackedLocks = activeGauges.size

        log.debug { "activeGauges: $activeGauges" }

        builder.up()
            .withDetail(DETAIL_ACTIVE, totalActive)
            .withDetail(DETAIL_TRACKED_LOCKS, trackedLocks)
    }
}

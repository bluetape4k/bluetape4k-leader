package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * `MongoHistoryTtlGauge`는 MongoDB history TTL index가 비활성(`ttlDays <= 0`)인지 Micrometer gauge로 노출하며, 비활성일 때 1.0을 기록합니다.
 *
 * @property config history collection 이름과 TTL 보존 일수를 제공하며, `ttlDays <= 0`이면 disabled gauge를 1.0으로 만듭니다.
 */
class MongoHistoryTtlGauge(
    private val config: MongoHistoryConfig,
    registry: MeterRegistry,
) {

    companion object: KLogging() {
        private const val GAUGE_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"
    }

    init {
        if (config.ttlDays <= 0) {
            log.warn {
                "MongoDB leader history TTL index is disabled (ttlDays=${config.ttlDays}). " +
                        "Data will accumulate indefinitely."
            }
        }

        Gauge.builder(GAUGE_TTL_DISABLED) { if (config.ttlDays <= 0) 1.0 else 0.0 }
            .description("1.0 when MongoDB leader history TTL index is disabled; 0.0 when active")
            .register(registry)
    }
}

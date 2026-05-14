package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * Micrometer gauge that reports whether the MongoDB history TTL index is disabled.
 *
 * Reads the live [MongoHistoryConfig.ttlDays] value on each scrape so that runtime
 * configuration changes are reflected immediately without a restart.
 *
 * ## Gauge semantics
 * | [MongoHistoryConfig.ttlDays] | Gauge value | Meaning |
 * |---|---|---|
 * | > 0 | `0.0` | TTL index active — data expires normally |
 * | ≤ 0 | `1.0` | TTL index disabled — data accumulates indefinitely |
 *
 * A gauge value of `1.0` triggers an alert when TTL is unexpectedly disabled.
 */
class MongoHistoryTtlGauge(
    private val config: MongoHistoryConfig,
    registry: MeterRegistry,
) {

    companion object : KLogging() {
        private const val GAUGE_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"
    }

    init {
        if (config.ttlDays <= 0) {
            log.warn { "MongoDB leader history TTL index is disabled (ttlDays=${config.ttlDays}). Data will accumulate indefinitely." }
        }

        Gauge.builder(GAUGE_TTL_DISABLED) { if (config.ttlDays <= 0) 1.0 else 0.0 }
            .description("1.0 when MongoDB leader history TTL index is disabled; 0.0 when active")
            .register(registry)
    }
}

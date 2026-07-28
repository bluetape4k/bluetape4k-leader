package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * `MongoHistoryTtlGauge`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property config MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
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

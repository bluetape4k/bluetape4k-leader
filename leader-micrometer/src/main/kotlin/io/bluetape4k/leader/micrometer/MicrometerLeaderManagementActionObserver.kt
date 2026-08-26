package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderManagementActionObserver
import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionPhase
import io.bluetape4k.leader.LeaderManagementQuarantineReason
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * sanitized management action observation을 저카디널리티 Micrometer meter로 변환합니다.
 *
 * lock 이름, actor, request, credential, token, exception은 meter identity와 tag에
 * 사용하지 않습니다. quarantine gauge는 worker의 실제 recovery callback에서만 감소합니다.
 */
class MicrometerLeaderManagementActionObserver(
    private val registry: MeterRegistry,
) : LeaderManagementActionObserver {

    private val counters = ConcurrentHashMap<MetricKey, Counter>()
    private val active = ConcurrentHashMap<MetricKey, AtomicInteger>()

    override fun onResult(observation: LeaderManagementActionObservation) {
        if (!observation.quarantined) return
        val reason = observation.quarantineReason ?: return
        val key = MetricKey(
            reason = reason.toMetricValue(),
            phase = observation.phase.toMetricValue(),
            surface = observation.surface.name.toMetricValue(),
        )
        counter(key).increment()
        gaugeValue(key).incrementAndGet()
    }

    override fun onQuarantineRecovered(observation: LeaderManagementActionObservation) {
        if (!observation.quarantined) return
        val reason = observation.quarantineReason ?: return
        val key = MetricKey(
            reason = reason.toMetricValue(),
            phase = observation.phase.toMetricValue(),
            surface = observation.surface.name.toMetricValue(),
        )
        gaugeValue(key).updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    private fun counter(key: MetricKey): Counter = counters.computeIfAbsent(key) {
        Counter.builder(MicrometerNames.MANAGEMENT_QUARANTINE)
            .description("sanitized leader management action quarantine events")
            .tag(MicrometerNames.MANAGEMENT_TAG_REASON, key.reason)
            .tag(MicrometerNames.MANAGEMENT_TAG_PHASE, key.phase)
            .tag(MicrometerNames.MANAGEMENT_TAG_SURFACE, key.surface)
            .register(registry)
    }

    private fun gaugeValue(key: MetricKey): AtomicInteger = active.computeIfAbsent(key) {
        val value = AtomicInteger()
        Gauge.builder(MicrometerNames.MANAGEMENT_QUARANTINE_ACTIVE, value) { it.get().toDouble() }
            .description("active sanitized leader management action quarantines")
            .tag(MicrometerNames.MANAGEMENT_TAG_REASON, key.reason)
            .tag(MicrometerNames.MANAGEMENT_TAG_PHASE, key.phase)
            .tag(MicrometerNames.MANAGEMENT_TAG_SURFACE, key.surface)
            .register(registry)
        value
    }

    private data class MetricKey(
        val reason: String,
        val phase: String,
        val surface: String,
    )

    private fun LeaderManagementQuarantineReason.toMetricValue(): String =
        name.lowercase(Locale.ROOT).replace('_', '-')

    private fun LeaderManagementActionPhase.toMetricValue(): String = when (this) {
        LeaderManagementActionPhase.RELEASE_STARTED -> "release"
        else -> name.lowercase(Locale.ROOT).replace('_', '-')
    }

    private fun String.toMetricValue(): String = lowercase(Locale.ROOT).replace('_', '-')
}

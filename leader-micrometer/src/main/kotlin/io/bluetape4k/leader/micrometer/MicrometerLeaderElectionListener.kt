package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * `MicrometerLeaderElectionListener`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property registry Micrometer observability 계약에서 사용하는 속성입니다.
 * @property tagSanitizer Micrometer observability 계약에서 사용하는 속성입니다.
 */
class MicrometerLeaderElectionListener(
    private val registry: MeterRegistry,
    private val tagSanitizer: LeaderMetricTagSanitizer,
) : LeaderElectionListener {

    constructor(registry: MeterRegistry): this(
        registry = registry,
        tagSanitizer = LeaderMetricTagSanitizer.Default,
    )

    constructor(registry: MeterRegistry, tagOptions: LeaderMetricTagOptions): this(
        registry = registry,
        tagSanitizer = LeaderMetricTagSanitizer.from(tagOptions),
    )

    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    override fun onElected(lockName: String) {
        counter(sanitizeLockName(lockName), EVENT_ELECTED).increment()
    }

    override fun onRevoked(lockName: String) {
        counter(sanitizeLockName(lockName), EVENT_REVOKED).increment()
    }

    override fun onSkipped(lockName: String) {
        counter(sanitizeLockName(lockName), EVENT_SKIPPED).increment()
    }

    private fun counter(lockName: String, event: String): Counter =
        counters.computeIfAbsent(lockName to event) { (name, value) ->
            Counter.builder(MicrometerNames.METER_LEADER_EVENTS)
                .tag(MicrometerNames.TAG_LOCK_NAME, name)
                .tag(MicrometerNames.TAG_EVENT, value)
                .register(registry)
        }

    private fun sanitizeLockName(lockName: String): String =
        tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName)

    private companion object {
        private const val EVENT_ELECTED = "elected"
        private const val EVENT_REVOKED = "revoked"
        private const val EVENT_SKIPPED = "skipped"
    }
}

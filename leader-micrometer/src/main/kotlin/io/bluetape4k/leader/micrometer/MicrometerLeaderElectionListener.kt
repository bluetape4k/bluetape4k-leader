package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * leader election의 elected, revoked, skipped 이벤트를 lock별 Micrometer counter로 기록합니다.
 *
 * @property registry 이벤트 counter를 등록하는 `MeterRegistry`입니다.
 * @property tagSanitizer counter의 `lock.name` tag를 생성할 때 적용할 sanitization 정책입니다.
 */
class MicrometerLeaderElectionListener(
    private val registry: MeterRegistry,
    private val tagSanitizer: LeaderMetricTagSanitizer,
): LeaderElectionListener {

    private companion object {
        private const val EVENT_ELECTED = "elected"
        private const val EVENT_REVOKED = "revoked"
        private const val EVENT_SKIPPED = "skipped"
    }

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

}

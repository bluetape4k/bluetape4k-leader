package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.logging.KLogging
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry

/**
 * leader election의 elected, revoked, skipped 이벤트를 Micrometer `Observation`으로 기록합니다.
 *
 * @property registry observation을 생성할 `ObservationRegistry`입니다. No-op registry이면 이벤트를 기록하지 않습니다.
 * @property options lock 이름을 observation에 포함할지와 해당 tag sanitization을 정하는 옵션입니다.
 */
class MicrometerObservationLeaderElectionListener(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
): LeaderElectionListener {

    private companion object: KLogging() {
        const val EVENT_ELECTED = "elected"
        const val EVENT_REVOKED = "revoked"
        const val EVENT_SKIPPED = "skipped"
    }

    private val tagSanitizer = LeaderMetricTagSanitizer.from(options.tagOptions)

    override fun onElected(lockName: String) {
        observeEvent(lockName, EVENT_ELECTED)
    }

    override fun onRevoked(lockName: String) {
        observeEvent(lockName, EVENT_REVOKED)
    }

    override fun onSkipped(lockName: String) {
        observeEvent(lockName, EVENT_SKIPPED)
    }

    private fun observeEvent(lockName: String, event: String) {
        if (registry.isNoop) return

        var observation = Observation.createNotStarted(OBSERVATION_LEADER_ELECTION_EVENT, registry)
            .lowCardinalityKeyValue(OBSERVATION_TAG_EVENT, event)

        if (options.includeLockName) {
            observation = observation.highCardinalityKeyValue(
                MicrometerNames.TAG_LOCK_NAME,
                tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName),
            )
        }

        observation.start().stop()
    }
}

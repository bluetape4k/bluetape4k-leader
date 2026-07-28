package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry

/**
 * `MicrometerObservationLeaderElectionListener`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property registry Micrometer observability 계약에서 사용하는 속성입니다.
 * @property options Micrometer observability 계약에서 사용하는 속성입니다.
 */
class MicrometerObservationLeaderElectionListener(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
) : LeaderElectionListener {

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

    private companion object {
        const val EVENT_ELECTED = "elected"
        const val EVENT_REVOKED = "revoked"
        const val EVENT_SKIPPED = "skipped"
    }
}

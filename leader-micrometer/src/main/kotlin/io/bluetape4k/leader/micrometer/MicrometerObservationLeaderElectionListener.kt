package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry

/**
 * Listener that records leader election lifecycle events as Micrometer Observations.
 *
 * ## Behavior / Contract
 * - Emits `leader.election.event` for `elected`, `revoked`, and `skipped` callbacks.
 * - Keeps `lock.name` high-cardinality and disabled by default.
 * - Does not configure tracing exporters; applications provide their own Micrometer tracing bridge.
 *
 * ```kotlin
 * val listener = MicrometerObservationLeaderElectionListener(observationRegistry)
 * registry.addListener(listener)
 * ```
 */
class MicrometerObservationLeaderElectionListener(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
) : LeaderElectionListener {

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
            observation = observation.highCardinalityKeyValue(MicrometerNames.TAG_LOCK_NAME, lockName)
        }

        observation.start().stop()
    }

    private companion object {
        const val EVENT_ELECTED = "elected"
        const val EVENT_REVOKED = "revoked"
        const val EVENT_SKIPPED = "skipped"
    }
}

package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Listener that records [LeaderElectionListener] events as Micrometer counters.
 *
 * ## Behavior / Contract
 * - Records the `leader.election.events` counter with `lock.name` and `event` tags.
 * - `event` values are `elected`, `revoked`, and `skipped`.
 * - Records only listener-based lifecycle events, separate from the `shedlock.leader.*` execution metrics of the direct elector decorator.
 *
 * ```kotlin
 * val listener = MicrometerLeaderElectionListener(registry)
 * val election = LocalLeaderElector().withListeners(listener)
 * election.runIfLeader("daily-job") { runJob() }
 * ```
 */
class MicrometerLeaderElectionListener(
    private val registry: MeterRegistry,
) : LeaderElectionListener {

    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    override fun onElected(lockName: String) {
        counter(lockName, EVENT_ELECTED).increment()
    }

    override fun onRevoked(lockName: String) {
        counter(lockName, EVENT_REVOKED).increment()
    }

    override fun onSkipped(lockName: String) {
        counter(lockName, EVENT_SKIPPED).increment()
    }

    private fun counter(lockName: String, event: String): Counter =
        counters.computeIfAbsent(lockName to event) { (name, value) ->
            Counter.builder(MicrometerNames.METER_LEADER_EVENTS)
                .tag(MicrometerNames.TAG_LOCK_NAME, name)
                .tag(MicrometerNames.TAG_EVENT, value)
                .register(registry)
        }

    private companion object {
        private const val EVENT_ELECTED = "elected"
        private const val EVENT_REVOKED = "revoked"
        private const val EVENT_SKIPPED = "skipped"
    }
}

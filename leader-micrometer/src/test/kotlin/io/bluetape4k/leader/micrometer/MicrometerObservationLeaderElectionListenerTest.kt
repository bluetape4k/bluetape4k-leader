package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerObservationLeaderElectionListenerTest {

    private lateinit var registry: ObservationRegistry
    private lateinit var handler: CollectingObservationHandler
    private lateinit var listener: MicrometerObservationLeaderElectionListener

    @BeforeEach
    fun setup() {
        handler = CollectingObservationHandler()
        registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(handler)
        listener = MicrometerObservationLeaderElectionListener(registry)
    }

    @Test
    fun `listener emits elected revoked and skipped events`() {
        listener.onElected("job-lock")
        listener.onRevoked("job-lock")
        listener.onSkipped("job-lock")

        handler.stopped.map { it.low[OBSERVATION_TAG_EVENT] } shouldBeEqualTo listOf("elected", "revoked", "skipped")
        handler.stopped.forEach { snapshot ->
            snapshot.name shouldBeEqualTo OBSERVATION_LEADER_ELECTION_EVENT
            snapshot.high.containsKey(MicrometerNames.TAG_LOCK_NAME).shouldBeEqualTo(false)
        }
    }

    @Test
    fun `listener emits lock name only when explicitly enabled`() {
        listener = MicrometerObservationLeaderElectionListener(
            registry = registry,
            options = LeaderObservationOptions(includeLockName = true),
        )

        listener.onElected("job-lock")

        val stopped = handler.singleStopped()
        stopped.high[MicrometerNames.TAG_LOCK_NAME] shouldBeEqualTo "job-lock"
    }

    @Test
    fun `noop registry does not emit handler callbacks`() {
        listener = MicrometerObservationLeaderElectionListener(ObservationRegistry.NOOP)

        listener.onElected("job-lock")
        listener.onRevoked("job-lock")
        listener.onSkipped("job-lock")

        handler.stopped.isEmpty().shouldBeTrue()
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<ObservationSnapshot>()

        override fun onStop(context: Observation.Context) {
            stopped += snapshot(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true

        fun singleStopped(): ObservationSnapshot {
            stopped.size shouldBeEqualTo 1
            return stopped[0]
        }
    }

    private data class ObservationSnapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
    )

    private companion object {
        fun snapshot(context: Observation.Context): ObservationSnapshot =
            ObservationSnapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
            )
    }
}

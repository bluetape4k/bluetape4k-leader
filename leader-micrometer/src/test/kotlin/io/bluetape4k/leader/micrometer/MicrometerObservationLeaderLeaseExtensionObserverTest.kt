package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseExtensionContext
import io.bluetape4k.leader.LeaderLeaseExtensionEvent
import io.bluetape4k.leader.LeaderLeaseExtensionExecution
import io.bluetape4k.leader.LeaderLeaseExtensionSource
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerObservationLeaderLeaseExtensionObserverTest {

    private lateinit var registry: ObservationRegistry
    private lateinit var handler: CollectingObservationHandler
    private lateinit var observer: MicrometerObservationLeaderLeaseExtensionObserver

    @BeforeEach
    fun setup() {
        handler = CollectingObservationHandler()
        registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(handler)
        observer = MicrometerObservationLeaderLeaseExtensionObserver(registry)
    }

    @Test
    fun `maps every extend outcome to bounded low cardinality tags`() {
        val outcomes = listOf(
            ExtendOutcome.Extended(Instant.EPOCH),
            ExtendOutcome.NotHeld,
            ExtendOutcome.WrongThread,
            ExtendOutcome.BackendError(IllegalStateException("backend detail")),
        )

        outcomes.forEach { outcome ->
            observer.onExtension(
                LeaderLeaseExtensionEvent(
                    source = LeaderLeaseExtensionSource.WATCHDOG,
                    execution = LeaderLeaseExtensionExecution.SUSPEND,
                    outcome = outcome,
                    elapsedNanos = 42L,
                    context = null,
                ),
            )
        }

        handler.stopped.size shouldBeEqualTo 4
        handler.stopped.map { it.name }.distinct() shouldBeEqualTo listOf("bluetape4k.leader.lease.extension")
        handler.stopped.map { it.low["source"] } shouldBeEqualTo listOf("watchdog", "watchdog", "watchdog", "watchdog")
        handler.stopped.map { it.low["execution"] } shouldBeEqualTo listOf("suspend", "suspend", "suspend", "suspend")
        handler.stopped.map { it.low["outcome"] } shouldBeEqualTo
            listOf("extended", "not_held", "wrong_thread", "backend_error")
        handler.stopped.map { it.low["result"] } shouldBeEqualTo
            listOf("success", "skipped", "error", "error")
        handler.stopped.forEach { snapshot ->
            snapshot.low.keys shouldBeEqualTo setOf("source", "execution", "outcome", "result")
            snapshot.low.keys.none { it.contains("elapsed", ignoreCase = true) }.shouldBeTrue()
            snapshot.high.keys.none { it.contains("elapsed", ignoreCase = true) }.shouldBeTrue()
        }
    }

    @Test
    fun `redacts identity by default and supports explicit high cardinality opt in`() {
        observer.onExtension(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.Extended(Instant.EPOCH),
                elapsedNanos = 7L,
                context = LeaderLeaseExtensionContext("secret-lock", "secret-leader"),
            ),
        )

        val defaultSnapshot = handler.singleStopped()
        defaultSnapshot.high.keys.none { it == "lock.name" || it == TAG_LEADER_ID }.shouldBeTrue()

        handler.clear()
        observer = MicrometerObservationLeaderLeaseExtensionObserver(
            registry = registry,
            options = LeaderObservationOptions(
                includeLockName = true,
                includeLeaderId = true,
                includeExceptionDetails = true,
            ),
        )
        observer.onExtension(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.Extended(Instant.EPOCH),
                elapsedNanos = 7L,
                context = LeaderLeaseExtensionContext("secret-lock", "secret-leader"),
            ),
        )

        val optedInSnapshot = handler.singleStopped()
        optedInSnapshot.high["lock.name"] shouldBeEqualTo "redacted-lock"
        optedInSnapshot.high[TAG_LEADER_ID] shouldBeEqualTo "redacted-leader"
    }

    @Test
    fun `records backend exception only when exception details are enabled`() {
        val failure = IllegalArgumentException("secret message")
        observer.onExtension(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.BackendError(failure),
                elapsedNanos = 11L,
                context = null,
            ),
        )

        handler.singleStopped().error.shouldBeNull()
        handler.clear()
        observer = MicrometerObservationLeaderLeaseExtensionObserver(
            registry = registry,
            options = LeaderObservationOptions(includeExceptionDetails = true),
        )
        observer.onExtension(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.BackendError(failure),
                elapsedNanos = 11L,
                context = null,
            ),
        )

        handler.singleStopped().error shouldBeEqualTo failure
    }

    @Test
    fun `noop registry does not emit observation callbacks`() {
        observer = MicrometerObservationLeaderLeaseExtensionObserver(ObservationRegistry.NOOP)

        observer.onExtension(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.NotHeld,
                elapsedNanos = 0L,
                context = null,
            ),
        )

        handler.stopped.isEmpty().shouldBeTrue()
        handler.errors.isEmpty().shouldBeTrue()
    }

    @Test
    fun `public observer ABI keeps one source constructor and no public tag constants`() {
        val observerType = MicrometerObservationLeaderLeaseExtensionObserver::class.java
        val sourceConstructors = observerType.constructors.filterNot { it.isSynthetic }

        sourceConstructors.size shouldBeEqualTo 1
        sourceConstructors.single().parameterTypes.toList() shouldBeEqualTo listOf(
            ObservationRegistry::class.java,
            LeaderObservationOptions::class.java,
        )
        observerType.fields.map { it.name }.none {
            it in setOf("OBSERVATION_NAME", "TAG_SOURCE", "TAG_EXECUTION", "TAG_OUTCOME", "TAG_RESULT")
        }.shouldBeTrue()
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<ObservationSnapshot>()
        val errors = CopyOnWriteArrayList<ObservationSnapshot>()

        override fun onStop(context: Observation.Context) {
            stopped += snapshot(context)
        }

        override fun onError(context: Observation.Context) {
            errors += snapshot(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true

        fun singleStopped(): ObservationSnapshot {
            stopped.size shouldBeEqualTo 1
            return stopped[0]
        }

        fun clear() {
            stopped.clear()
            errors.clear()
        }
    }

    private data class ObservationSnapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
        val error: Throwable?,
    )

    private companion object {
        fun snapshot(context: Observation.Context): ObservationSnapshot =
            ObservationSnapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
                error = context.error,
            )
    }
}

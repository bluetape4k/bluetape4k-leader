package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.leader.metrics.LeaderAopMetricsContext
import io.bluetape4k.leader.metrics.SkipReason
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerObservationLeaderAopMetricsRecorderTest {

    private lateinit var registry: ObservationRegistry
    private lateinit var handler: CollectingObservationHandler
    private lateinit var recorder: MicrometerObservationLeaderAopMetricsRecorder

    private val options = LeaderElectionOptions.Default

    @BeforeEach
    fun setup() {
        handler = CollectingObservationHandler()
        registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(handler)
        recorder = MicrometerObservationLeaderAopMetricsRecorder(registry)
    }

    @Test
    fun `onLockAcquired emits acquired observation with elapsed key`() {
        recorder.onLockAcquired("job-lock", options, 12.milliseconds)

        val stopped = handler.singleStopped()
        stopped.name shouldBeEqualTo OBSERVATION_LEADER_AOP_ACQUIRE
        stopped.low[OBSERVATION_TAG_OPERATION] shouldBeEqualTo "acquire"
        stopped.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "acquired"
        stopped.high[OBSERVATION_TAG_ACQUIRE_ELAPSED_MS] shouldBeEqualTo "12"
    }

    @Test
    fun `onLockNotAcquired emits skipped acquire observation with reason`() {
        recorder.onLockNotAcquired("job-lock", options, SkipReason.BACKEND_ERROR)

        val stopped = handler.singleStopped()
        stopped.name shouldBeEqualTo OBSERVATION_LEADER_AOP_ACQUIRE
        stopped.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "skipped"
        stopped.low[OBSERVATION_TAG_REASON] shouldBeEqualTo SkipReason.BACKEND_ERROR.name
    }

    @Test
    fun `onTaskFinished emits success execution observation with elapsed key`() {
        recorder.onTaskFinished("job-lock", 34.milliseconds)

        val stopped = handler.singleStopped()
        stopped.name shouldBeEqualTo OBSERVATION_LEADER_AOP_EXECUTION
        stopped.low[OBSERVATION_TAG_OPERATION] shouldBeEqualTo "execute"
        stopped.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "success"
        stopped.high[OBSERVATION_TAG_EXECUTION_ELAPSED_MS] shouldBeEqualTo "34"
    }

    @Test
    fun `onTaskFailed records exception class without raw throwable by default`() {
        recorder.onTaskFailed("job-lock", 56.milliseconds, IllegalStateException("secret tenant id"))

        val stopped = handler.singleStopped()
        stopped.name shouldBeEqualTo OBSERVATION_LEADER_AOP_EXECUTION
        stopped.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "error"
        stopped.low[OBSERVATION_TAG_EXCEPTION] shouldBeEqualTo "IllegalStateException"
        stopped.error.shouldBeNull()
        handler.errors.isEmpty().shouldBeTrue()
    }

    @Test
    fun `includeExceptionDetails records raw throwable for non-cancellation failure`() {
        val failure = IllegalArgumentException("raw-message")
        recorder = MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = LeaderObservationOptions(includeExceptionDetails = true),
        )

        recorder.onTaskFailed("job-lock", 1.milliseconds, failure)

        val stopped = handler.singleStopped()
        stopped.error shouldBeEqualTo failure
        handler.errors.size shouldBeEqualTo 1
        handler.errors[0].error shouldBeEqualTo failure
    }

    @Test
    fun `cancellation emits cancelled outcome without raw error`() {
        recorder.onTaskStarted("job-lock")
        recorder.onTaskFailed("job-lock", 2.milliseconds, CancellationException("cancelled"))

        val stopped = handler.singleStopped()
        stopped.low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "cancelled"
        stopped.low.containsKey(OBSERVATION_TAG_EXCEPTION).shouldBeEqualTo(false)
        stopped.error.shouldBeNull()
        handler.errors.isEmpty().shouldBeTrue()
    }

    @Test
    fun `lock name and leader id are absent by default`() {
        val context = LeaderAopMetricsContext.Identified("leader-a", LeaderIdSource.LITERAL)

        recorder.onTaskFinished("job-lock", 3.milliseconds, context)

        val stopped = handler.singleStopped()
        stopped.high.containsKey(MicrometerNames.TAG_LOCK_NAME).shouldBeEqualTo(false)
        stopped.high.containsKey(TAG_LEADER_ID).shouldBeEqualTo(false)
        stopped.low.containsKey(TAG_LEADER_ID_SOURCE).shouldBeEqualTo(false)
    }

    @Test
    fun `lock name and leader id are present only when explicitly enabled and identified`() {
        recorder = MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = LeaderObservationOptions(includeLockName = true, includeLeaderId = true),
        )
        val context = LeaderAopMetricsContext.Identified("leader-a", LeaderIdSource.LITERAL)

        recorder.onTaskFinished("job-lock", 3.milliseconds, context)

        val stopped = handler.singleStopped()
        stopped.high[MicrometerNames.TAG_LOCK_NAME] shouldBeEqualTo "job-lock"
        stopped.high[TAG_LEADER_ID] shouldBeEqualTo "leader-a"
        stopped.low[TAG_LEADER_ID_SOURCE] shouldBeEqualTo LeaderIdSource.LITERAL.name
    }

    @Test
    fun `unknown context does not emit leader id even when enabled`() {
        recorder = MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = LeaderObservationOptions(includeLeaderId = true),
        )

        recorder.onTaskFinished("job-lock", 3.milliseconds, LeaderAopMetricsContext.Unknown)

        val stopped = handler.singleStopped()
        stopped.high.containsKey(TAG_LEADER_ID).shouldBeEqualTo(false)
        stopped.low.containsKey(TAG_LEADER_ID_SOURCE).shouldBeEqualTo(false)
    }

    @Test
    fun `backend failure sequence emits skipped acquire and standalone execution error`() {
        recorder.onLockAttempt("job-lock", options)
        recorder.onLockNotAcquired("job-lock", options, SkipReason.BACKEND_ERROR)
        recorder.onTaskFailed("job-lock", 4.milliseconds, IllegalStateException("backend"))

        handler.stopped.size shouldBeEqualTo 2
        handler.stopped[0].name shouldBeEqualTo OBSERVATION_LEADER_AOP_ACQUIRE
        handler.stopped[0].low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "skipped"
        handler.stopped[1].name shouldBeEqualTo OBSERVATION_LEADER_AOP_EXECUTION
        handler.stopped[1].low[OBSERVATION_TAG_OUTCOME] shouldBeEqualTo "error"
    }

    @Test
    fun `noop registry does not emit handler callbacks`() {
        recorder = MicrometerObservationLeaderAopMetricsRecorder(ObservationRegistry.NOOP)

        recorder.onLockAcquired("job-lock", options, 1.milliseconds)
        recorder.onTaskFinished("job-lock", 1.milliseconds)

        handler.stopped.isEmpty().shouldBeTrue()
        handler.errors.isEmpty().shouldBeTrue()
    }

    @Test
    fun `terminal observations do not leave a current observation scope`() {
        registry.currentObservation.shouldBeNull()

        recorder.onTaskStarted("job-lock")
        recorder.onTaskFinished("job-lock", 1.milliseconds)

        registry.currentObservation.shouldBeNull()
    }

    @Test
    fun `same-lock concurrent terminal callbacks are race-free`() {
        MultithreadingTester()
            .workers(4)
            .rounds(25)
            .add {
                recorder.onTaskFinished("job-lock", 1.milliseconds)
            }
            .run()

        handler.stopped.size shouldBeEqualTo 100
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

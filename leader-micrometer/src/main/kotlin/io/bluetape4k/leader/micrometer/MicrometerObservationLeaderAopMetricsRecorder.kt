package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsContext
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import kotlin.time.Duration

/**
 * `MicrometerObservationLeaderAopMetricsRecorder`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property registry Micrometer observability 계약에서 사용하는 속성입니다.
 * @property options Micrometer observability 계약에서 사용하는 속성입니다.
 */
class MicrometerObservationLeaderAopMetricsRecorder(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
) : LeaderAopMetricsRecorder {

    private val tagSanitizer = LeaderMetricTagSanitizer.from(options.tagOptions)

    override fun onLockAttempt(name: String, options: LeaderElectionOptions) = Unit

    override fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {
        onLockAcquired(name, options, acquireElapsed, LeaderAopMetricsContext.Unknown)
    }

    override fun onLockAcquired(
        name: String,
        options: LeaderElectionOptions,
        acquireElapsed: Duration,
        context: LeaderAopMetricsContext,
    ) {
        if (registry.isNoop) return

        terminalObservation(OBSERVATION_LEADER_AOP_ACQUIRE, name, context)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OPERATION, OPERATION_ACQUIRE)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OUTCOME, OUTCOME_ACQUIRED)
            .highCardinalityKeyValue(OBSERVATION_TAG_ACQUIRE_ELAPSED_MS, acquireElapsed.inWholeMilliseconds.toString())
            .start()
            .stop()
    }

    override fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {
        onLockNotAcquired(name, options, reason, LeaderAopMetricsContext.Unknown)
    }

    override fun onLockNotAcquired(
        name: String,
        options: LeaderElectionOptions,
        reason: SkipReason,
        context: LeaderAopMetricsContext,
    ) {
        if (registry.isNoop) return

        terminalObservation(OBSERVATION_LEADER_AOP_ACQUIRE, name, context)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OPERATION, OPERATION_ACQUIRE)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OUTCOME, OUTCOME_SKIPPED)
            .lowCardinalityKeyValue(OBSERVATION_TAG_REASON, reason.name)
            .start()
            .stop()
    }

    override fun onTaskStarted(name: String) = Unit

    override fun onTaskFinished(name: String, executionTime: Duration) {
        onTaskFinished(name, executionTime, LeaderAopMetricsContext.Unknown)
    }

    override fun onTaskFinished(name: String, executionTime: Duration, context: LeaderAopMetricsContext) {
        if (registry.isNoop) return

        terminalObservation(OBSERVATION_LEADER_AOP_EXECUTION, name, context)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OPERATION, OPERATION_EXECUTE)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OUTCOME, OUTCOME_SUCCESS)
            .highCardinalityKeyValue(OBSERVATION_TAG_EXECUTION_ELAPSED_MS, executionTime.inWholeMilliseconds.toString())
            .start()
            .stop()
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        onTaskFailed(name, executionTime, throwable, LeaderAopMetricsContext.Unknown)
    }

    override fun onTaskFailed(
        name: String,
        executionTime: Duration,
        throwable: Throwable,
        context: LeaderAopMetricsContext,
    ) {
        if (registry.isNoop) return

        val observation = terminalObservation(OBSERVATION_LEADER_AOP_EXECUTION, name, context)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OPERATION, OPERATION_EXECUTE)
            .lowCardinalityKeyValue(OBSERVATION_TAG_OUTCOME, if (throwable is CancellationException) OUTCOME_CANCELLED else OUTCOME_ERROR)
            .highCardinalityKeyValue(OBSERVATION_TAG_EXECUTION_ELAPSED_MS, executionTime.inWholeMilliseconds.toString())

        if (throwable !is CancellationException) {
            observation.lowCardinalityKeyValue(
                OBSERVATION_TAG_EXCEPTION,
                throwable::class.simpleName ?: MicrometerNames.UNKNOWN_EXCEPTION,
            )
            if (options.includeExceptionDetails) {
                observation.error(throwable)
            }
        }

        observation.start().stop()
    }

    private fun terminalObservation(
        observationName: String,
        lockName: String,
        context: LeaderAopMetricsContext,
    ): Observation {
        var observation = Observation.createNotStarted(observationName, registry)

        if (options.includeLockName) {
            observation = observation.highCardinalityKeyValue(
                MicrometerNames.TAG_LOCK_NAME,
                tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, lockName),
            )
        }

        if (options.includeLeaderId && context is LeaderAopMetricsContext.Identified) {
            observation = observation
                .highCardinalityKeyValue(TAG_LEADER_ID, tagSanitizer.sanitize(TAG_LEADER_ID, context.leaderId))
                .lowCardinalityKeyValue(TAG_LEADER_ID_SOURCE, context.leaderIdSource.name)
        }

        return observation
    }

    private companion object {
        const val OPERATION_ACQUIRE = "acquire"
        const val OPERATION_EXECUTE = "execute"
        const val OUTCOME_ACQUIRED = "acquired"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_CANCELLED = "cancelled"
    }
}

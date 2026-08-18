package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseExtensionEvent
import io.bluetape4k.leader.LeaderLeaseExtensionObserver
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.Locale

/**
 * lease-extension event를 Micrometer terminal Observation으로 변환합니다.
 *
 * Observation은 callback 안에서만 생성·종료하며, lease ownership과 extension 결과에는
 * 영향을 주지 않습니다. lock name과 leader ID는 기존 redaction 정책을 따릅니다.
 */
class MicrometerObservationLeaderLeaseExtensionObserver(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
) : LeaderLeaseExtensionObserver {

    private val tagSanitizer = LeaderMetricTagSanitizer.from(options.tagOptions)

    override fun onExtension(event: LeaderLeaseExtensionEvent) {
        if (registry.isNoop) return

        val mapping = event.outcome.toObservationMapping()
        var observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
            .lowCardinalityKeyValue(TAG_SOURCE, event.source.name.lowercase(Locale.ROOT))
            .lowCardinalityKeyValue(TAG_EXECUTION, event.execution.name.lowercase(Locale.ROOT))
            .lowCardinalityKeyValue(TAG_OUTCOME, mapping.outcome)
            .lowCardinalityKeyValue(TAG_RESULT, mapping.result)

        event.context?.let { context ->
            val auditLeaderId = context.auditLeaderId
            if (options.includeLockName) {
                observation = observation.highCardinalityKeyValue(
                    MicrometerNames.TAG_LOCK_NAME,
                    tagSanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, context.lockName),
                )
            }
            if (options.includeLeaderId && auditLeaderId != null) {
                observation = observation.highCardinalityKeyValue(
                    TAG_LEADER_ID,
                    tagSanitizer.sanitize(TAG_LEADER_ID, auditLeaderId),
                )
            }
        }

        val backendError = event.outcome as? ExtendOutcome.BackendError
        if (options.includeExceptionDetails && backendError != null) {
            observation = observation.error(backendError.cause)
        }

        observation.start().stop()
    }

    private companion object {
        private const val OBSERVATION_NAME = "bluetape4k.leader.lease.extension"
        private const val TAG_SOURCE = "source"
        private const val TAG_EXECUTION = "execution"
        private const val TAG_OUTCOME = "outcome"
        private const val TAG_RESULT = "result"
    }
}

private data class LeaseExtensionObservationMapping(
    val outcome: String,
    val result: String,
)

private fun ExtendOutcome.toObservationMapping(): LeaseExtensionObservationMapping =
    when (this) {
        is ExtendOutcome.Extended -> LeaseExtensionObservationMapping("extended", "success")
        ExtendOutcome.NotHeld -> LeaseExtensionObservationMapping("not_held", "skipped")
        ExtendOutcome.WrongThread -> LeaseExtensionObservationMapping("wrong_thread", "error")
        is ExtendOutcome.BackendError -> LeaseExtensionObservationMapping("backend_error", "error")
    }

package io.bluetape4k.leader.micrometer

import java.io.Serializable

/**
 * Options for Micrometer Observation based leader tracing.
 *
 * ## Behavior / Contract
 * - Raw lock names and leader IDs are high-cardinality values and are disabled by default.
 * - Raw exception details are disabled by default because tracing exporters may include
 *   exception messages and stack traces.
 * - Enabling any option affects only Observation key values; existing Micrometer meters are unchanged.
 *
 * ```kotlin
 * val recorder = MicrometerObservationLeaderAopMetricsRecorder(
 *     registry = observationRegistry,
 *     options = LeaderObservationOptions(includeLockName = true),
 * )
 * ```
 */
data class LeaderObservationOptions(
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }
}

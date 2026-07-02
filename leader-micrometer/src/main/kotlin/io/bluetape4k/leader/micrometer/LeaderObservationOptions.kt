package io.bluetape4k.leader.micrometer

import java.io.Serializable

/**
 * Options for Micrometer Observation based leader tracing.
 *
 * ## Behavior / Contract
 * - Lock names and leader IDs are high-cardinality values and are disabled by default.
 * - Raw exception details are disabled by default because tracing exporters may include
 *   exception messages and stack traces.
 * - Enabling any option affects only Observation key values; existing Micrometer meters are unchanged.
 * - When lock names or leader IDs are enabled, [tagOptions] controls their exported values.
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
    val tagOptions: LeaderMetricTagOptions = LeaderMetricTagOptions.Default,
) : Serializable {

    /**
     * Binary-compatible constructor for callers compiled before tag options were added.
     */
    constructor(
        includeLockName: Boolean,
        includeLeaderId: Boolean,
        includeExceptionDetails: Boolean,
    ) : this(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
        tagOptions = LeaderMetricTagOptions.Default,
    )

    companion object {
        private const val serialVersionUID = 1L
    }
}

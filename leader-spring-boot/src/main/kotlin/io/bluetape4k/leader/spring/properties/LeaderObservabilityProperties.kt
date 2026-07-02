package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/**
 * Spring Boot observability options for leader election.
 *
 * ## Behavior / Contract
 * - [enabled] controls leader observability support beans such as the status registry and
 *   event-publisher facade.
 * - [lockNames] seeds the status registry with statically known lock names so the Actuator
 *   endpoint can report them before the first runtime event is observed.
 * - [tracing] controls optional Micrometer Observation bridge beans.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     observability:
 *       enabled: true
 *       lock-names:
 *         - batch-job
 *         - migration-gate
 *       tracing:
 *         enabled: true
 * ```
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
    val tracing: LeaderTracingProperties = LeaderTracingProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Micrometer Observation bridge options for leader election.
 *
 * ## Behavior / Contract
 * - [enabled] registers Observation recorder/listener beans when `ObservationRegistry` is present.
 * - [includeLockName] adds raw lock names as high-cardinality observation data.
 * - [includeLeaderId] adds leader IDs only when the caller provides identified metrics context.
 * - [includeExceptionDetails] attaches the raw throwable to failed execution observations.
 */
data class LeaderTracingProperties(
    val enabled: Boolean = true,
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

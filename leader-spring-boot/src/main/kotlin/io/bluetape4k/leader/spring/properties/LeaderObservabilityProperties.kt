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
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     observability:
 *       enabled: true
 *       lock-names:
 *         - batch-job
 *         - migration-gate
 * ```
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

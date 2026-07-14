package io.bluetape4k.leader.spring.properties

import java.io.Serializable
import java.time.Duration

/**
 * Spring Boot observability options for leader election.
 *
 * ## Behavior / Contract
 * - [enabled] controls leader observability support beans such as the status registry and
 *   event-publisher facade.
 * - [lockNames] seeds the status registry with statically known lock names so the Actuator
 *   endpoint can report them before the first runtime event is observed.
 * - [tracing] controls optional Micrometer Observation bridge beans.
 * - [health] controls the opt-in known-lock readiness contributor.
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
 *       health:
 *         enabled: true
 *         lease-warning-threshold: 10s
 * ```
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
    val tracing: LeaderTracingProperties = LeaderTracingProperties(),
    val health: LeaderObservabilityHealthProperties = LeaderObservabilityHealthProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Known-lock readiness health options.
 *
 * The contributor is disabled by default because each health invocation calls
 * `LeaderElector.state` once for every JVM-known lock name.
 *
 * @property enabled whether to register the `leaderElectionReadiness` health contributor.
 * @property leaseWarningThreshold occupied leases expiring within this duration are reported as
 * `OUT_OF_SERVICE`. Zero only flags leases that are already expired at query time.
 */
data class LeaderObservabilityHealthProperties(
    val enabled: Boolean = false,
    val leaseWarningThreshold: Duration = Duration.ofSeconds(10),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(!leaseWarningThreshold.isNegative) {
            "observability.health.leaseWarningThreshold must not be negative: $leaseWarningThreshold"
        }
    }
}

/**
 * Micrometer Observation bridge options for leader election.
 *
 * ## Behavior / Contract
 * - [enabled] registers Observation recorder/listener beans when `ObservationRegistry` is present.
 * - [includeLockName] adds tag-policy-sanitized lock names as high-cardinality observation data.
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

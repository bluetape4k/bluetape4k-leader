package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/** Selects the mutually exclusive route-authority model. */
enum class LeaderRouteAuthorityMode {
    /** Compare the guarded [io.bluetape4k.leader.LeaderSlot] with a leader state snapshot. */
    STATE,

    /** Delegate the decision to exactly one application-provided authority bean. */
    CUSTOM,
}

/** Safe HTTP statuses accepted for fail-closed route rejection. */
enum class LeaderRouteRejectionStatus(
    val value: Int,
) {
    NOT_FOUND(404),
    CONFLICT(409),
    LOCKED(423),
    SERVICE_UNAVAILABLE(503),
}

/**
 * Opt-in Spring MVC and WebFlux leader route-guard options.
 *
 * [STATE][LeaderRouteAuthorityMode.STATE] and [CUSTOM][LeaderRouteAuthorityMode.CUSTOM]
 * are exclusive. Auto-configuration rejects mixed authority configuration at startup.
 * STATE additionally requires a selected elector that declares audit-identity
 * state support.
 */
data class LeaderRouteGuardProperties(
    val enabled: Boolean = false,
    val authorityMode: LeaderRouteAuthorityMode = LeaderRouteAuthorityMode.STATE,
    val electorBean: String = "",
    val rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

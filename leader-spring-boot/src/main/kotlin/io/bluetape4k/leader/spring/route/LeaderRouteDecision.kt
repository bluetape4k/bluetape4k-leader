package io.bluetape4k.leader.spring.route

/** Result of a passive leader-route authorization decision. */
sealed interface LeaderRouteDecision {

    /** Local leadership was established and the protected handler may run. */
    data object Allowed : LeaderRouteDecision

    /** The authority completed normally but did not establish local leadership. */
    data object NotLeader : LeaderRouteDecision

    /** Leadership could not be determined safely, so the request must fail closed. */
    data object Unavailable : LeaderRouteDecision
}

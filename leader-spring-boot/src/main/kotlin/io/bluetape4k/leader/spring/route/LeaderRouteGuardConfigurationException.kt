package io.bluetape4k.leader.spring.route

/** Invalid, unsafe, or ambiguous leader route-guard startup configuration. */
class LeaderRouteGuardConfigurationException(
    val code: String,
    detail: String,
) : IllegalStateException("$code: $detail") {

    companion object {
        const val AUTHORITY_MIXED: String = "LEADER_ROUTE_AUTHORITY_MIXED"
        const val AUTHORITY_MISSING: String = "LEADER_ROUTE_AUTHORITY_MISSING"
        const val AUTHORITY_AMBIGUOUS: String = "LEADER_ROUTE_AUTHORITY_AMBIGUOUS"
        const val ELECTOR_MISSING: String = "LEADER_ROUTE_ELECTOR_MISSING"
        const val ELECTOR_AMBIGUOUS: String = "LEADER_ROUTE_ELECTOR_AMBIGUOUS"
        const val ELECTOR_STATE_UNSUPPORTED: String = "LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED"
    }
}

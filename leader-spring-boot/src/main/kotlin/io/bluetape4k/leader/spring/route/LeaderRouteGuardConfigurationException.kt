package io.bluetape4k.leader.spring.route

/**
 * `LeaderRouteGuardConfigurationException`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property code Spring Boot integration 계약에서 사용하는 속성입니다.
 */
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

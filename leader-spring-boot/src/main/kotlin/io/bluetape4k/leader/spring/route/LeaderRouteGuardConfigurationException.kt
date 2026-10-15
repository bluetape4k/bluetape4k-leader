package io.bluetape4k.leader.spring.route

/** route guard bean 조합이나 backend capability가 설정 계약을 만족하지 않을 때 던지는 예외입니다.
 *
 * @property code machine-readable route guard 오류 코드입니다.
 */
class LeaderRouteGuardConfigurationException(
    val code: String,
    detail: String,
): IllegalStateException("$code: $detail") {

    companion object {
        const val AUTHORITY_MIXED: String = "LEADER_ROUTE_AUTHORITY_MIXED"
        const val AUTHORITY_MISSING: String = "LEADER_ROUTE_AUTHORITY_MISSING"
        const val AUTHORITY_AMBIGUOUS: String = "LEADER_ROUTE_AUTHORITY_AMBIGUOUS"
        const val ELECTOR_MISSING: String = "LEADER_ROUTE_ELECTOR_MISSING"
        const val ELECTOR_AMBIGUOUS: String = "LEADER_ROUTE_ELECTOR_AMBIGUOUS"
        const val ELECTOR_STATE_UNSUPPORTED: String = "LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED"
        const val ELECTOR_LEASE_UNSUPPORTED: String = "LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED"
        const val LEASE_REDIRECT_INCOMPATIBLE: String = "LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE"
        const val LEASE_ADMISSION_REJECTED: String = "LEADER_ROUTE_LEASE_ADMISSION_REJECTED"
        const val LEASE_SUSPEND_UNSUPPORTED: String = "LEADER_ROUTE_LEASE_SUSPEND_UNSUPPORTED"
        const val LEASE_CLEANUP_TIMEOUT: String = "LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT"
        const val LEASE_DRAIN_TIMEOUT: String = "LEADER_ROUTE_LEASE_DRAIN_TIMEOUT"

        /** 새 route 진단 상수 추가 전 `0.5.0`의 Java serialization 계약입니다. */
        private const val serialVersionUID: Long = 2956475360686774009L
    }
}

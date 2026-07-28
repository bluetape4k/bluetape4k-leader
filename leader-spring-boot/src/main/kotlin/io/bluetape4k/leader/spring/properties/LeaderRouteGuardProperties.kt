package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/**
 * `LeaderRouteAuthorityMode`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value Spring Boot integration 계약에서 사용하는 속성입니다.
 */
enum class LeaderRouteAuthorityMode {
    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    STATE,

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    CUSTOM,
}

/**
 * `LeaderRouteRejectionStatus`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value Spring Boot integration 계약에서 사용하는 속성입니다.
 */
enum class LeaderRouteRejectionStatus(
    val value: Int,
) {
    NOT_FOUND(404),
    CONFLICT(409),
    LOCKED(423),
    SERVICE_UNAVAILABLE(503),
}

/**
 * `LeaderRouteGuardProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property authorityMode Spring Boot integration 계약에서 `authorityMode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property electorBean Spring Boot integration 계약에서 `electorBean` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property rejectionStatus Spring Boot integration 계약에서 `rejectionStatus` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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

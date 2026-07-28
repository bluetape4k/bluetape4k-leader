package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/**
 * `LeaderDiagnosticsProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property strict Spring Boot integration 계약에서 `strict` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeBeanNames Spring Boot integration 계약에서 `includeBeanNames` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderDiagnosticsProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val includeBeanNames: Boolean = true,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

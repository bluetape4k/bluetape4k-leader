package io.bluetape4k.leader.spring

import java.time.Duration

/**
 * `ConsulLeaderProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property keyPrefix Spring Boot integration 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sessionNamePrefix Spring Boot integration 계약에서 `sessionNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockDelay Spring Boot integration 계약에서 `lockDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ConsulLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
) {
    companion object {
        const val DefaultKeyPrefix: String = "bluetape4k/leader"
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"
    }
}

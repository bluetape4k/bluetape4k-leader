package io.bluetape4k.leader.spring

/**
 * `EtcdLeaderProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property keyPrefix Spring Boot integration 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class EtcdLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
) {
    companion object {
        const val DefaultKeyPrefix: String = "/bluetape4k/leader"
    }
}

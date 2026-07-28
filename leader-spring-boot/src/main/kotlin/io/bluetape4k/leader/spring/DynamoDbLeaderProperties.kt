package io.bluetape4k.leader.spring

import java.time.Duration

/**
 * `DynamoDbLeaderProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property tableName Spring Boot integration 계약에서 `tableName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix Spring Boot integration 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay Spring Boot integration 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlPadding Spring Boot integration 계약에서 `ttlPadding` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property clockSkewTolerance Spring Boot integration 계약에서 `clockSkewTolerance` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class DynamoDbLeaderProperties(
    val tableName: String = DefaultTableName,
    val keyPrefix: String = DefaultKeyPrefix,
    val retryDelay: Duration = Duration.ofMillis(50),
    val ttlPadding: Duration = Duration.ofSeconds(60),
    val clockSkewTolerance: Duration = Duration.ofSeconds(5),
) {
    companion object {
        const val DefaultTableName: String = "bluetape4k_leader_locks"
        const val DefaultKeyPrefix: String = "leader"
    }
}

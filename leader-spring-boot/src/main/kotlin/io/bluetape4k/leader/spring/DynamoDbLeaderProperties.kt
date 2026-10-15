package io.bluetape4k.leader.spring

import java.io.Serializable
import java.time.Duration

/**
 * Spring Boot의 DynamoDB leader backend가 lock item과 TTL을 계산하는 설정입니다.
 *
 * @property tableName lock item을 저장할 DynamoDB table 이름입니다. 기본값은 `bluetape4k_leader_locks`입니다.
 * @property keyPrefix DynamoDB partition key 앞에 붙이는 prefix입니다. 기본값은 `leader`입니다.
 * @property retryDelay conditional write 재시도 사이의 지연 시간입니다. 기본값은 50밀리초입니다.
 * @property ttlPadding lease TTL 계산에 clock skew 여유로 더하는 시간입니다. 기본값은 60초입니다.
 * @property clockSkewTolerance local clock과 DynamoDB server time 차이를 허용하는 시간입니다. 기본값은 5초입니다.
 */
data class DynamoDbLeaderProperties(
    val tableName: String = DefaultTableName,
    val keyPrefix: String = DefaultKeyPrefix,
    val retryDelay: Duration = Duration.ofMillis(50),
    val ttlPadding: Duration = Duration.ofSeconds(60),
    val clockSkewTolerance: Duration = Duration.ofSeconds(5),
): Serializable {
    companion object {
        const val DefaultTableName: String = "bluetape4k_leader_locks"
        const val DefaultKeyPrefix: String = "leader"

        private const val serialVersionUID: Long = 1L
    }
}

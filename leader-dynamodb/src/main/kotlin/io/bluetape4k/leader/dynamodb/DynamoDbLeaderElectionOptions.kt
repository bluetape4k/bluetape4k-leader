package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.support.requirePositiveDuration
import io.bluetape4k.support.requireZeroOrPositiveDuration
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `DynamoDbLeaderElectionOptions`는 DynamoDB backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions lease·대기·최소 lease·자동 연장과 nodeId를 포함한 공통 단일 선거 설정입니다.
 * @property tableName lock item을 저장할 DynamoDB 테이블 이름이며 기본값은 `bluetape4k_leader_locks`입니다.
 * @property keyPrefix partition key 앞에 붙일 prefix이며 기본값은 `leader`입니다.
 * @property retryDelay 경합 뒤 재시도까지의 양수 지연이며 기본값은 50ms입니다.
 * @property ttlPadding DynamoDB TTL attribute가 lease 만료보다 늦게 삭제되도록 더하는 양수 여유 시간이며 기본값은 60초입니다.
 * @property clockSkewTolerance 조건부 쓰기의 안전 시각에서 빼는 시계 오차 허용량이며 0 이상, 기본값은 5초입니다.
 */
data class DynamoDbLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val tableName: String = DefaultTableName,
    val keyPrefix: String = DefaultKeyPrefix,
    val retryDelay: Duration = 50.milliseconds,
    val ttlPadding: Duration = 60.seconds,
    val clockSkewTolerance: Duration = 5.seconds,
): Serializable {

    init {
        DynamoDbKeys.validateTableName(tableName)
        DynamoDbKeys.validateKeyPrefix(keyPrefix)
        retryDelay.requirePositiveDuration("retryDelay")
        ttlPadding.requirePositiveDuration("ttlPadding")
        clockSkewTolerance.requireZeroOrPositiveDuration("clockSkewTolerance")
        require(leaderOptions.leaseTime > clockSkewTolerance * 2) {
            "leaseTime must be greater than 2 * clockSkewTolerance: " +
                    "leaseTime=${leaderOptions.leaseTime}, clockSkewTolerance=$clockSkewTolerance"
        }
    }

    companion object {
        const val DefaultTableName: String = "bluetape4k_leader_locks"
        const val DefaultKeyPrefix: String = "leader"

        @JvmField
        val Default = DynamoDbLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `DynamoDbLeaderElectionOptions`는 DynamoDB backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions DynamoDB backend 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property tableName DynamoDB backend 계약에서 `tableName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix DynamoDB backend 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay DynamoDB backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlPadding DynamoDB backend 계약에서 `ttlPadding` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property clockSkewTolerance DynamoDB backend 계약에서 `clockSkewTolerance` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class DynamoDbLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val tableName: String = DefaultTableName,
    val keyPrefix: String = DefaultKeyPrefix,
    val retryDelay: Duration = 50.milliseconds,
    val ttlPadding: Duration = 60.seconds,
    val clockSkewTolerance: Duration = 5.seconds,
) : Serializable {

    init {
        DynamoDbKeys.validateTableName(tableName)
        DynamoDbKeys.validateKeyPrefix(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
        ttlPadding.requireGt(Duration.ZERO, "ttlPadding")
        require(clockSkewTolerance >= Duration.ZERO) { "clockSkewTolerance must be >= 0: $clockSkewTolerance" }
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

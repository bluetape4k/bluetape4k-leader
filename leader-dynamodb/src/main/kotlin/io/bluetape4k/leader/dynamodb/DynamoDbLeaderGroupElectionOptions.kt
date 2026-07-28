package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `DynamoDbLeaderGroupElectionOptions`는 DynamoDB backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions DynamoDB backend 계약에서 `leaderGroupOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property tableName DynamoDB backend 계약에서 `tableName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix DynamoDB backend 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay DynamoDB backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlPadding DynamoDB backend 계약에서 `ttlPadding` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property clockSkewTolerance DynamoDB backend 계약에서 `clockSkewTolerance` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class DynamoDbLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val tableName: String = DynamoDbLeaderElectionOptions.DefaultTableName,
    val keyPrefix: String = DynamoDbLeaderElectionOptions.DefaultKeyPrefix,
    val retryDelay: Duration = 50.milliseconds,
    val ttlPadding: Duration = 60.seconds,
    val clockSkewTolerance: Duration = 5.seconds,
) : Serializable {

    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        DynamoDbKeys.validateTableName(tableName)
        DynamoDbKeys.validateKeyPrefix(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
        ttlPadding.requireGt(Duration.ZERO, "ttlPadding")
        require(clockSkewTolerance >= Duration.ZERO) { "clockSkewTolerance must be >= 0: $clockSkewTolerance" }
        require(leaderGroupOptions.leaseTime > clockSkewTolerance * 2) {
            "leaseTime must be greater than 2 * clockSkewTolerance: " +
                "leaseTime=${leaderGroupOptions.leaseTime}, clockSkewTolerance=$clockSkewTolerance"
        }
    }

    companion object {
        @JvmField
        val Default = DynamoDbLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}

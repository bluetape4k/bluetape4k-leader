package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Options for DynamoDB slot-based multi-leader election.
 *
 * @property leaderGroupOptions common group-election options.
 * @property tableName DynamoDB table that stores group slot rows.
 * @property keyPrefix prefix applied to logical lock keys.
 * @property retryDelay upper bound for full-jitter acquisition retry delays.
 * @property ttlPadding extra TTL cleanup padding added after logical lease expiry.
 * @property clockSkewTolerance tolerated host-clock skew subtracted from takeover checks.
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
        tableName.requireNotBlank("tableName")
        keyPrefix.requireNotBlank("keyPrefix")
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

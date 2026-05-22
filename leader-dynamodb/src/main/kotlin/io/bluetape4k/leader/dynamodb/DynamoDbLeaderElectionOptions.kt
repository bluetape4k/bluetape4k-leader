package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Options for DynamoDB single-leader election.
 *
 * The DynamoDB table is caller-provisioned. It must have a string partition key
 * named `lockName` and should enable TTL on the numeric `ttl` attribute.
 *
 * @property leaderOptions common single-leader election options.
 * @property tableName DynamoDB table that stores lock rows.
 * @property keyPrefix prefix applied to logical lock keys.
 * @property retryDelay upper bound for full-jitter acquisition retry delays.
 * @property ttlPadding extra TTL cleanup padding added after logical lease expiry.
 * @property clockSkewTolerance tolerated host-clock skew subtracted from takeover checks.
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
        tableName.requireNotBlank("tableName")
        keyPrefix.requireNotBlank("keyPrefix")
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

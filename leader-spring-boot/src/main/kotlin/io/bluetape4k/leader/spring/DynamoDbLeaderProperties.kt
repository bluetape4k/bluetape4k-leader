package io.bluetape4k.leader.spring

import java.time.Duration

/**
 * DynamoDB backend properties.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     dynamodb:
 *       table-name: bluetape4k_leader_locks
 *       key-prefix: leader
 *       retry-delay: 50ms
 *       ttl-padding: 60s
 *       clock-skew-tolerance: 5s
 * ```
 *
 * @property tableName caller-provisioned DynamoDB table with `lockName` as string partition key.
 * @property keyPrefix prefix applied before logical lock names.
 * @property retryDelay upper bound for full-jitter acquisition retry delay.
 * @property ttlPadding extra DynamoDB TTL padding after logical lease expiry.
 * @property clockSkewTolerance tolerated host-clock skew subtracted from takeover checks.
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

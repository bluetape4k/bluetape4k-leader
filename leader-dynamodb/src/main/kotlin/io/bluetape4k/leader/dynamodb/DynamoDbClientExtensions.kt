package io.bluetape4k.leader.dynamodb

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/**
 * Runs a suspending action only while this async DynamoDB client holds leadership.
 *
 * ## Behavior / Contract
 * Returns the action result when leadership is acquired. Returns `null` when another node holds the lock
 * or acquisition times out according to [options].
 *
 * ```kotlin
 * val result = dynamoDbAsync.suspendRunIfLeader("nightly-job") {
 *     rebuildIndex()
 * }
 * ```
 */
suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = DynamoDbSuspendLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs a suspending action while this async DynamoDB client owns one leader-group slot.
 *
 * ## Behavior / Contract
 * Returns the action result when a group slot is acquired. Returns `null` when all slots are occupied
 * or acquisition times out according to [options].
 *
 * ```kotlin
 * val result = dynamoDbAsync.suspendRunIfLeaderGroup("partition-worker") {
 *     processPartition()
 * }
 * ```
 */
suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = DynamoDbSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)

package io.bluetape4k.leader.dynamodb

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = DynamoDbSuspendLeaderElector(this, options).runIfLeader(lockName, action)

suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = DynamoDbSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)

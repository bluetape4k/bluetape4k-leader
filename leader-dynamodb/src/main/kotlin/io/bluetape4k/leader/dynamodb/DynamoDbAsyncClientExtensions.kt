package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.validateLockName
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient


/** [this]에서 [lockName]을 suspend 방식으로 획득했을 때만 [action]을 실행하며 경합 시 null입니다. */
suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbSuspendLeaderElector(this, options)
        .runIfLeader(lockName, action)
}

/** [this]의 그룹 슬롯을 suspend 방식으로 획득했을 때만 [action]을 실행하며 슬롯 경합 시 null입니다. */
suspend fun <T> DynamoDbAsyncClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbSuspendLeaderGroupElector(this, options)
        .runIfLeader(lockName, action)
}

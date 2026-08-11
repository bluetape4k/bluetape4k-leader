package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB suspend leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(
            DynamoDbContractSupport.dynamoDbAsync,
            DynamoDbLeaderElectionOptions(
                leaderOptions = options,
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

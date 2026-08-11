package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB async leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbAsyncLeaderElectorLeaderIdContractTest : AbstractAsyncLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): AsyncLeaderElector =
        DynamoDbLeaderElector(
            DynamoDbContractSupport.dynamoDb,
            DynamoDbLeaderElectionOptions(
                leaderOptions = options,
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

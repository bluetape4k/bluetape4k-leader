package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB blocking leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbLeaderElectorLeaderIdContractTest : AbstractLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): LeaderElector =
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

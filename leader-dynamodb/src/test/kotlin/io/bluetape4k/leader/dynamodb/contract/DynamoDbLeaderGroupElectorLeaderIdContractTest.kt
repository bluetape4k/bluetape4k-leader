package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB blocking group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        DynamoDbLeaderGroupElector(
            DynamoDbContractSupport.dynamoDb,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

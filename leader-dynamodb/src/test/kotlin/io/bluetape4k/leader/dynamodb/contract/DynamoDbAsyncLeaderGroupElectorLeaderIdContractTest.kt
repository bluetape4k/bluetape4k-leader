package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB async group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbAsyncLeaderGroupElectorLeaderIdContractTest : AbstractAsyncLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): AsyncLeaderGroupElector =
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

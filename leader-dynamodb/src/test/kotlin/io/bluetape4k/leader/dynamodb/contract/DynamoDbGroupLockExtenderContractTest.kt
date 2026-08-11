package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB blocking group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {
    override val elector: LeaderGroupElector =
        DynamoDbLeaderGroupElector(
            DynamoDbContractSupport.dynamoDb,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

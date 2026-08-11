package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB suspend group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(
            DynamoDbContractSupport.dynamoDbAsync,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

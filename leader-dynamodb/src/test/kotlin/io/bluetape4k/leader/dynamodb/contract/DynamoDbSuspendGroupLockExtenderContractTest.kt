package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB suspend group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {
    override val elector: SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(
            DynamoDbContractSupport.dynamoDbAsync,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

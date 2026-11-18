package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB suspend LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbSuspendLockExtenderContractTest: AbstractSuspendLockExtenderContractTest() {

    companion object: KLogging()

    override val elector: SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(
            DynamoDbContractSupport.asyncClient,
            DynamoDbLeaderElectionOptions(
                tableName = DynamoDbContractSupport.tableName,
                keyPrefix = DynamoDbContractSupport.keyPrefix(),
                ttlPadding = 1.seconds,
                clockSkewTolerance = 10.milliseconds,
            ),
        )
}

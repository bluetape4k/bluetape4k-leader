package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderGroupElector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Direct DynamoDB virtual-thread wrapper coverage for single and group slot paths.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbVirtualThreadContractTest {

    @Test
    fun singleVirtualWrapperPropagatesLeaderIdAndReleases() {
        val elector = DynamoDbVirtualThreadLeaderElector(
            DynamoDbLeaderElector(
                DynamoDbContractSupport.dynamoDb,
                DynamoDbLeaderElectionOptions(
                    tableName = DynamoDbContractSupport.tableName,
                    keyPrefix = DynamoDbContractSupport.keyPrefix(),
                    ttlPadding = 1.seconds,
                    clockSkewTolerance = 10.milliseconds,
                ),
            ),
        )
        val lockName = "dynamodb-virtual-single-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "dynamodb-single-a"),
        ) { "single-ok" }.toCompletableFuture().join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "single-ok"
        first.leaderId shouldBeEqualTo "dynamodb-single-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "dynamodb-single-b"),
        ) { "single-reacquired" }.toCompletableFuture().join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "single-reacquired"
        second.leaderId shouldBeEqualTo "dynamodb-single-b"
    }

    @Test
    fun groupVirtualWrapperPropagatesLeaderIdAndReleases() {
        val elector = DynamoDbVirtualThreadLeaderGroupElector(
            DynamoDbLeaderGroupElector(
                DynamoDbContractSupport.dynamoDb,
                DynamoDbLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                    tableName = DynamoDbContractSupport.tableName,
                    keyPrefix = DynamoDbContractSupport.keyPrefix(),
                    ttlPadding = 1.seconds,
                    clockSkewTolerance = 10.milliseconds,
                ),
            ),
        )
        val lockName = "dynamodb-virtual-group-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "dynamodb-group-a"),
        ) { "group-ok" }.toCompletableFuture().join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "group-ok"
        first.leaderId shouldBeEqualTo "dynamodb-group-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "dynamodb-group-b"),
        ) { "group-reacquired" }.toCompletableFuture().join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "group-reacquired"
        second.leaderId shouldBeEqualTo "dynamodb-group-b"
    }
}

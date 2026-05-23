package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Creates blocking single-leader DynamoDB electors from a shared client.
 *
 * ## Behavior / Contract
 * Each [create] call keeps the factory's table/key-prefix settings and replaces only
 * [LeaderElectionOptions]. The created elector returns `null` when leadership is not acquired.
 *
 * ```kotlin
 * val factory = DynamoDbLeaderElectorFactory(dynamoDb)
 * val elector = factory.create(LeaderElectionOptions.Default)
 * ```
 */
class DynamoDbLeaderElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : LeaderElectorFactory {
    override fun create(options: LeaderElectionOptions): LeaderElector =
        DynamoDbLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * Creates blocking multi-leader DynamoDB group electors from a shared client.
 *
 * ## Behavior / Contract
 * Each [create] call keeps the factory's table/key-prefix settings and replaces only
 * [LeaderGroupElectionOptions]. The created elector returns `null` when no group slot is acquired.
 *
 * ```kotlin
 * val factory = DynamoDbLeaderGroupElectorFactory(dynamoDb)
 * val elector = factory.create(LeaderGroupElectionOptions.Default)
 * ```
 */
class DynamoDbLeaderGroupElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {
    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        DynamoDbLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

/**
 * Creates coroutine single-leader DynamoDB electors from a shared async client.
 *
 * ## Behavior / Contract
 * Each [create] call keeps the factory's table/key-prefix settings and replaces only
 * [LeaderElectionOptions]. The created suspend elector returns `null` when leadership is not acquired.
 *
 * ```kotlin
 * val factory = DynamoDbSuspendLeaderElectorFactory(dynamoDbAsync)
 * val elector = factory.create(LeaderElectionOptions.Default)
 * ```
 */
class DynamoDbSuspendLeaderElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {
    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * Creates coroutine multi-leader DynamoDB group electors from a shared async client.
 *
 * ## Behavior / Contract
 * Each [create] call keeps the factory's table/key-prefix settings and replaces only
 * [LeaderGroupElectionOptions]. The created suspend group elector returns `null` when no slot is acquired.
 *
 * ```kotlin
 * val factory = DynamoDbSuspendLeaderGroupElectorFactory(dynamoDbAsync)
 * val elector = factory.create(LeaderGroupElectionOptions.Default)
 * ```
 */
class DynamoDbSuspendLeaderGroupElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {
    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

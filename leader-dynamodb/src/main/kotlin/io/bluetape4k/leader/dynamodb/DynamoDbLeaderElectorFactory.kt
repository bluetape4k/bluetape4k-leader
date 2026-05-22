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

class DynamoDbLeaderElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : LeaderElectorFactory {
    override fun create(options: LeaderElectionOptions): LeaderElector =
        DynamoDbLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

class DynamoDbLeaderGroupElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {
    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        DynamoDbLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

class DynamoDbSuspendLeaderElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {
    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

class DynamoDbSuspendLeaderGroupElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {
    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

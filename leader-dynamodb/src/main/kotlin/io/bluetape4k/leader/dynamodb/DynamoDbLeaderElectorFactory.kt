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
 * `DynamoDbLeaderElectorFactory`는 synchronous DynamoDB client와 기본 옵션으로 단일 elector를 생성합니다.
 *
 * @property client 단일 lock item을 다룰 synchronous DynamoDB client입니다.
 * @property baseOptions create 시 leader 옵션만 교체해 사용할 기본 단일 설정입니다.
 */
class DynamoDbLeaderElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
): LeaderElectorFactory {
    override fun create(options: LeaderElectionOptions): LeaderElector =
        DynamoDbLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * `DynamoDbLeaderGroupElectorFactory`는 synchronous DynamoDB client와 그룹 기본 옵션으로 slot elector를 생성합니다.
 *
 * @property client 그룹 slot item을 다룰 synchronous DynamoDB client입니다.
 * @property baseOptions create 시 그룹 옵션만 교체해 사용할 기본 그룹 설정입니다.
 */
class DynamoDbLeaderGroupElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
): LeaderGroupElectorFactory {
    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        DynamoDbLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

/**
 * `DynamoDbSuspendLeaderElectorFactory`는 asynchronous DynamoDB client와 기본 옵션으로 suspend 단일 elector를 생성합니다.
 *
 * @property client 단일 lock item을 다룰 asynchronous DynamoDB client입니다.
 * @property baseOptions create 시 leader 옵션만 교체해 사용할 기본 단일 설정입니다.
 */
class DynamoDbSuspendLeaderElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
): SuspendLeaderElectorFactory {
    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * `DynamoDbSuspendLeaderGroupElectorFactory`는 asynchronous DynamoDB client와 그룹 기본 옵션으로 suspend slot elector를 생성합니다.
 *
 * @property client 그룹 slot item을 다룰 asynchronous DynamoDB client입니다.
 * @property baseOptions create 시 그룹 옵션만 교체해 사용할 기본 그룹 설정입니다.
 */
class DynamoDbSuspendLeaderGroupElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElectorFactory {
    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

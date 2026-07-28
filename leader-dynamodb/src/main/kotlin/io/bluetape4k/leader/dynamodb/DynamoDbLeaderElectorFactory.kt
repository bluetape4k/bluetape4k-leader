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
 * `DynamoDbLeaderElectorFactory`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbLeaderElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : LeaderElectorFactory {
    override fun create(options: LeaderElectionOptions): LeaderElector =
        DynamoDbLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * `DynamoDbLeaderGroupElectorFactory`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbLeaderGroupElectorFactory(
    private val client: DynamoDbClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {
    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        DynamoDbLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

/**
 * `DynamoDbSuspendLeaderElectorFactory`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbSuspendLeaderElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {
    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        DynamoDbSuspendLeaderElector(client, baseOptions.copy(leaderOptions = options))
}

/**
 * `DynamoDbSuspendLeaderGroupElectorFactory`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbSuspendLeaderGroupElectorFactory(
    private val client: DynamoDbAsyncClient,
    private val baseOptions: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {
    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(client, baseOptions.copy(leaderGroupOptions = options))
}

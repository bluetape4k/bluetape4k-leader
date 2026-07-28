package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.bson.Document

/**
 * `MongoSuspendLeaderGroupElectorFactory`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property groupCollection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property coroutineGroupCollection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoSuspendLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        MongoSuspendLeaderGroupElector(
            groupCollection,
            coroutineGroupCollection,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

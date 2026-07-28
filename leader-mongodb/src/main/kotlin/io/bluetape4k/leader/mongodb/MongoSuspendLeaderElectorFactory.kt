package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.bson.Document

/**
 * `MongoSuspendLeaderElectorFactory`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property collection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoSuspendLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        MongoSuspendLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

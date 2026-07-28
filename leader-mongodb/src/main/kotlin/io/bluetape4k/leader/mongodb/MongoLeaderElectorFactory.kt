package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.bson.Document

/**
 * `MongoLeaderElectorFactory`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property collection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        MongoLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

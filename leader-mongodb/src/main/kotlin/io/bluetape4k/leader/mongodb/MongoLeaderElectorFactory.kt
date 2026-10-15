package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.bson.Document

/**
 * `MongoLeaderElectorFactory`는 공유 MongoDB collection과 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property collection 단일 lock 문서를 저장할 MongoDB collection입니다.
 * @property baseOptions `create` 때 core options로 교체할 기본 MongoDB 옵션이며 `retryDelay`는 유지됩니다.
 */
class MongoLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        MongoLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

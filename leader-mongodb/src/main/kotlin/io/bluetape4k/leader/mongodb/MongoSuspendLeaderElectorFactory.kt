package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.bson.Document

/**
 * `MongoSuspendLeaderElectorFactory`는 공유 MongoDB collection과 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property collection suspend 단일 lock 문서를 저장할 coroutine MongoDB collection입니다.
 * @property baseOptions `create` 때 core options로 교체할 기본 MongoDB 옵션이며 `retryDelay`는 유지됩니다.
 */
class MongoSuspendLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        MongoSuspendLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

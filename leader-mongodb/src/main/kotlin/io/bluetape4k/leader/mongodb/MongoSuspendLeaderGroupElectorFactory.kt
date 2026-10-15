package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.bson.Document
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection

/**
 * `MongoSuspendLeaderGroupElectorFactory`는 공유 MongoDB collections와 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property groupCollection live slot 조회에 사용할 동기 MongoDB collection입니다.
 * @property coroutineGroupCollection slot 획득·해제에 사용할 동일 namespace의 coroutine collection입니다.
 * @property baseOptions `create` 때 그룹 core options로 교체할 기본 옵션이며 `retryDelay`는 유지됩니다.
 */
class MongoSuspendLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        MongoSuspendLeaderGroupElector(
            groupCollection,
            coroutineGroupCollection,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.bson.Document

/**
 * `MongoLeaderGroupElectorFactory`는 공유 MongoDB collection과 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property groupCollection 그룹 slot lock 문서를 저장할 MongoDB collection입니다.
 * @property baseOptions `create` 때 그룹 core options로 교체할 기본 옵션이며 `retryDelay`는 유지됩니다.
 */
class MongoLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        MongoLeaderGroupElector(groupCollection, baseOptions.copy(leaderGroupOptions = options))
}

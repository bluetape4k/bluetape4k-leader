package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.bson.Document

/**
 * [MongoLeaderGroupElector] 팩토리 — MongoDB sync 클라이언트 기반 다중 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = MongoLeaderGroupElectionFactory(groupCollection)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * `baseOptions.copy(leaderGroupOptions = options)`로 `maxLeaders`/`waitTime`/`leaseTime` 을 매 호출
 * 갈아끼우면서 `retryDelay`는 보존한다.
 *
 * @param groupCollection 그룹 락 컬렉션
 * @param baseOptions MongoDB 고유 옵션 기본값
 */
class MongoLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        MongoLeaderGroupElector(groupCollection, baseOptions.copy(leaderGroupOptions = options))
}

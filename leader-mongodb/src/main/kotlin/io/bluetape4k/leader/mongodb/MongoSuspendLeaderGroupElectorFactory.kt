package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.bson.Document

/**
 * [MongoSuspendLeaderGroupElector] 팩토리 — MongoDB 기반 suspend 복수 리더 선출.
 *
 * ## 옵션 처리
 * MongoDB 백엔드 고유 옵션은 [baseOptions]로 factory 생성 시 고정되며,
 * 매 호출마다 `baseOptions.copy(leaderGroupOptions = options)`로 `maxLeaders`/`waitTime`/`leaseTime` 만 교체한다.
 *
 * `MongoSuspendLeaderGroupElector(...)` 호출은 companion `suspend operator fun invoke`로 라우팅되어
 * [io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.ensureIndexes]가 함께 실행된다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = MongoSuspendLeaderGroupElectorFactory(syncCollection, coroutineCollection)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param groupCollection 락 컬렉션 (sync 드라이버 — activeCount에 사용)
 * @param coroutineGroupCollection 락 컬렉션 (coroutine 드라이버 — tryLock/unlock에 사용)
 * @param baseOptions MongoDB 고유 옵션 기본값
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

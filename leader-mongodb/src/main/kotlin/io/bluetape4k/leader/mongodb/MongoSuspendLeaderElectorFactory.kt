package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.bson.Document

/**
 * [MongoSuspendLeaderElector] 팩토리 — MongoDB 분산 락 기반 suspend 단일 리더 선출.
 *
 * ## 옵션 처리
 * MongoDB 백엔드 고유 옵션 (`retryDelay`)은 [baseOptions]로 factory 생성 시 고정되며,
 * 매 호출마다 `baseOptions.copy(leaderOptions = options)`로 `waitTime`/`leaseTime` 만 교체한다.
 *
 * `MongoSuspendLeaderElector(...)` 호출은 companion `suspend operator fun invoke`로 라우팅되어
 * [io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.ensureIndexes]가 함께 실행된다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = MongoSuspendLeaderElectorFactory(coroutineCollection)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param collection 락 컬렉션 (coroutine 드라이버)
 * @param baseOptions MongoDB 고유 옵션 기본값
 */
class MongoSuspendLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        MongoSuspendLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

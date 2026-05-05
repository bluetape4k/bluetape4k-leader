package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.bson.Document

/**
 * [MongoLeaderElector] 팩토리 — MongoDB sync 클라이언트 기반 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val collection: MongoCollection<Document> = database.getCollection("leader_lock")
 * val factory = MongoLeaderElectionFactory(collection)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## 옵션 처리
 * AOP 어드바이스가 전달하는 [LeaderElectionOptions]는 `waitTime`/`leaseTime` 만 포함한다.
 * MongoDB 백엔드 고유 옵션 (`retryDelay`)은 [baseOptions]를 통해 factory 생성 시점에 고정되며,
 * 매 호출마다 `baseOptions.copy(leaderOptions = options)`로 [LeaderElectionOptions]만 교체한다.
 *
 * `MongoLeaderElection(...)` 호출은 companion `operator fun invoke`로 라우팅되어
 * [MongoLock.ensureIndexes]가 함께 실행된다.
 *
 * @param collection 락 컬렉션
 * @param baseOptions MongoDB 고유 옵션 기본값. AOP 호출마다 `waitTime`/`leaseTime` 만 갈아끼운다
 */
class MongoLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        MongoLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}

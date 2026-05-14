package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [ExposedJdbcLeaderElector] 팩토리 — Exposed JDBC 기반 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val db: Database = Database.connect(dataSource)
 * val factory = ExposedJdbcLeaderElectionFactory(db)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## 옵션 처리
 * AOP 어드바이스가 전달하는 [LeaderElectionOptions]는 `waitTime`/`leaseTime`만 포함한다.
 * Exposed 백엔드 고유 옵션 (`retryStrategy`, `lockOwner`)은 [baseOptions]를 통해
 * factory 생성 시점에 고정되며, 매 호출마다 `baseOptions.copy(leaderOptions = options)`로 갈아끼운다.
 *
 * `ExposedJdbcLeaderElector(...)` 호출은 companion `operator fun invoke`로 라우팅되어
 * `ExposedJdbcSchemaInitializer.ensureSchema(db)`가 함께 실행된다.
 *
 * @param db Exposed [Database]
 * @param baseOptions Exposed 고유 옵션 기본값
 */
class ExposedJdbcLeaderElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ExposedJdbcLeaderElector(db, baseOptions.copy(leaderOptions = options))
}

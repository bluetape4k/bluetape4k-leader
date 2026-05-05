package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [ExposedJdbcLeaderGroupElector] 팩토리 — Exposed JDBC 기반 다중 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = ExposedJdbcLeaderGroupElectionFactory(db)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * `baseOptions.copy(leaderGroupOptions = options)`로 `maxLeaders`/`waitTime`/`leaseTime`만 매 호출
 * 갈아끼우면서 `retryStrategy`/`recordHistory`/`lockOwner`는 보존한다.
 *
 * @param db Exposed [Database]
 * @param baseOptions Exposed 고유 옵션 기본값
 */
class ExposedJdbcLeaderGroupElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ExposedJdbcLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}

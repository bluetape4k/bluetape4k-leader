package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * `ExposedJdbcLeaderGroupElectorFactory`는 JDBC Database와 그룹 기본 옵션으로 slot elector를 생성합니다.
 *
 * @property db group elector 인스턴스가 공유할 Exposed JDBC 데이터베이스입니다.
 * @property baseOptions 각 그룹 lock name에 적용할 기본 maxLeaders·lease·retry 설정입니다.
 */
class ExposedJdbcLeaderGroupElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ExposedJdbcLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}

package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * `ExposedJdbcLeaderElectorFactory`는 JDBC Database와 기본 옵션으로 단일 elector를 생성하고 schema 초기화를 위임합니다.
 *
 * @property db elector 인스턴스가 공유할 Exposed JDBC 데이터베이스입니다.
 * @property baseOptions 각 lock name에 적용할 기본 선거 설정입니다. 호출 시 별도 옵션이 있으면 해당 값이 우선합니다.
 */
class ExposedJdbcLeaderElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ExposedJdbcLeaderElector(db, baseOptions.copy(leaderOptions = options))
}

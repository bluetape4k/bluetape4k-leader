package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * `ExposedJdbcLeaderGroupElectorFactory`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedJdbcLeaderGroupElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ExposedJdbcLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}

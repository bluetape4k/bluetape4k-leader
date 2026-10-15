package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * `ExposedR2DbcSuspendLeaderGroupElectorFactory`는 R2DBC Database와 그룹 기본 옵션으로 suspend slot elector를 생성합니다.
 *
 * @property db suspend group elector가 공유할 Exposed R2DBC 데이터베이스입니다.
 * @property baseOptions 각 그룹 lock name에 적용할 기본 maxLeaders·lease·retry 설정입니다.
 */
class ExposedR2DbcSuspendLeaderGroupElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ExposedR2DbcSuspendLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}

package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * `ExposedR2DbcSuspendLeaderElectorFactory`는 R2DBC Database와 기본 옵션으로 suspend 단일 elector를 생성합니다.
 *
 * @property db suspend elector가 공유할 Exposed R2DBC 데이터베이스입니다.
 * @property baseOptions 각 lock name에 적용할 기본 lease·retry 설정입니다. [create]가 leader 옵션을 교체합니다.
 */
class ExposedR2DbcSuspendLeaderElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ExposedR2DbcSuspendLeaderElector(db, baseOptions.copy(leaderOptions = options))
}

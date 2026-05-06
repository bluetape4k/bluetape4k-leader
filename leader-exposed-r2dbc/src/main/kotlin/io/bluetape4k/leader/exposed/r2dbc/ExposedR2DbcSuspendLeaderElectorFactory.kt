package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * [ExposedR2DbcSuspendLeaderElector] 팩토리 — Exposed R2DBC 기반 suspend 단일 리더 선출.
 *
 * ## 옵션 처리
 * Exposed R2DBC 백엔드 고유 옵션 (`retryStrategy`, `recordHistory`)은 [baseOptions]로 factory 생성 시 고정되며,
 * 매 호출마다 `baseOptions.copy(leaderOptions = options)`로 `waitTime`/`leaseTime` 만 교체한다.
 *
 * `ExposedR2DbcSuspendLeaderElector(...)` 호출은 companion `suspend operator fun invoke`로 라우팅되어
 * [ExposedR2dbcSchemaInitializer.ensureSchema]가 함께 실행된다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = ExposedR2DbcSuspendLeaderElectorFactory(r2dbcDatabase)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param baseOptions Exposed R2DBC 고유 옵션 기본값
 */
class ExposedR2DbcSuspendLeaderElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ExposedR2DbcSuspendLeaderElector(db, baseOptions.copy(leaderOptions = options))
}

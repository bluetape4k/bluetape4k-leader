package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * [ExposedR2DbcSuspendLeaderGroupElector] 팩토리 — Exposed R2DBC 기반 suspend 복수 리더 선출.
 *
 * ## 옵션 처리
 * Exposed R2DBC 백엔드 고유 옵션 (`retryStrategy`, `recordHistory`)은 [baseOptions]로 factory 생성 시 고정되며,
 * 매 호출마다 `baseOptions.copy(leaderGroupOptions = options)`로 `maxLeaders`/`waitTime`/`leaseTime` 만 교체한다.
 *
 * `ExposedR2DbcSuspendLeaderGroupElector(...)` 호출은 companion `suspend operator fun invoke`로 라우팅되어
 * [ExposedR2dbcSchemaInitializer.ensureSchema]가 함께 실행된다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = ExposedR2DbcSuspendLeaderGroupElectorFactory(r2dbcDatabase)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param baseOptions Exposed R2DBC 고유 옵션 기본값
 */
class ExposedR2DbcSuspendLeaderGroupElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ExposedR2DbcSuspendLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}

package io.bluetape4k.leader.examples.migration

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * 롤링 배포 중 DB 마이그레이션 게이트.
 *
 * ## 동작/계약
 *
 * 다중 인스턴스(K8s pod) 환경에서 단 1개만 마이그레이션을 실행하도록 보장.
 * `runMigration` 은 다음 4단계로 동작:
 *
 * 1. **Precheck**: [isApplied] 호출 — true 면 [Outcome.AlreadyApplied] 즉시 반환 (락 시도 없음)
 * 2. **Lock 획득**: `runIfLeader` 로 옵션의 [MigrationGateOptions.waitTime] 동안 락 획득 시도
 * 3. **In-lock recheck**: 락 내부에서 [isApplied] 재확인 — true 면 마이그레이션 skip ([Outcome.AlreadyApplied])
 * 4. **Migration 실행**: [migration] lambda 실행 → 성공 시 [Outcome.Migrated], 예외 시 [Outcome.Failed]
 * 5. **Post-skip check**: 락 미획득 시 [isApplied] 한 번 더 확인 — true 면 [Outcome.AlreadyApplied], 아니면 [Outcome.Skipped]
 *
 * **중요**: 현재 backend ([ExposedJdbcLeaderElector]) 는 lease auto-extend 미지원.
 * 마이그레이션이 [MigrationGateOptions.leaseTime] 보다 길어지면 다른 인스턴스가 락을 재획득하여
 * 중복 실행될 수 있음. [migration] 은 idempotent 하게 작성하거나,
 * marker 기록을 같은 transaction 안에서 수행하여 안전성 확보 권장.
 *
 * **isApplied() 예외 처리**: 마커 조회 실패 (DB 연결 오류, 권한 문제 등) 는 "미적용" 과
 * 동등하게 처리하지 않고 [Outcome.Failed] 로 매핑하여 호출자가 startup 실패 처리 가능.
 * "마커 상태 불명" ≠ "미적용".
 *
 * ```kotlin
 * val gate = MigrationGate(db, MigrationGateOptions(
 *     nodeId = HOSTNAME,
 *     lockName = "schema-v3",
 *     waitTime = 30.seconds,
 *     leaseTime = 5.minutes,
 * ))
 *
 * val outcome = gate.runMigration(
 *     migrationId = "schema-v3",
 *     isApplied = { schemaMarkerExists(db, "v3") },
 *     migration = {
 *         transaction(db) {
 *             SchemaUtils.createMissingTablesAndColumns(UsersTable)
 *             SchemaMarkerTable.insert { it[version] = "v3" }   // marker 같은 tx
 *         }
 *     },
 * )
 * ```
 */
class MigrationGate(
    db: Database,
    val options: MigrationGateOptions,
) {
    companion object: KLogging()

    private val elector: ExposedJdbcLeaderElector = ExposedJdbcLeaderElector(
        db = db,
        options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = options.waitTime,
                leaseTime = options.leaseTime,
                nodeId = options.nodeId,
            ),
            lockOwner = options.nodeId,
        ),
    )

    /**
     * 마이그레이션 실행 게이트.
     *
     * @param migrationId 마이그레이션 식별자 (로그·outcome 에 기록). 비어있으면 안 됨.
     * @param isApplied 마이그레이션 완료 여부 확인 콜백. precheck/in-lock/post-skip 3회 호출됨.
     * @param migration 실제 마이그레이션 작업. idempotent 하게 작성 권장. 예외 시 락 자동 해제.
     * @return 4가지 분기 [Outcome]
     */
    fun runMigration(
        migrationId: String,
        isApplied: () -> Boolean,
        migration: () -> Unit,
    ): Outcome {
        migrationId.requireNotBlank("migrationId")
        val started = System.currentTimeMillis()

        // 1. Precheck — isApplied() 예외는 그대로 Failed 로 매핑 (silent swallow 금지)
        val precheckApplied = try {
            isApplied()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] migration=$migrationId: precheck isApplied() 실패" }
            return Outcome.Failed(migrationId, e, System.currentTimeMillis() - started)
        }
        if (precheckApplied) {
            log.info { "[${options.nodeId}] migration=$migrationId: precheck — 이미 적용됨" }
            return Outcome.AlreadyApplied(migrationId)
        }

        // 2. Lock 획득 + in-lock recheck + migration
        val inLockOutcome: Outcome? = try {
            elector.runIfLeader(options.lockName) {
                log.info { "[${options.nodeId}] migration=$migrationId: 리더 선출 — in-lock recheck" }
                if (isApplied()) {
                    log.info { "[${options.nodeId}] migration=$migrationId: in-lock recheck — 이미 적용됨" }
                    return@runIfLeader Outcome.AlreadyApplied(migrationId)
                }
                log.info { "[${options.nodeId}] migration=$migrationId: 마이그레이션 실행 시작" }
                migration()
                val elapsed = System.currentTimeMillis() - started
                log.info { "[${options.nodeId}] migration=$migrationId: 마이그레이션 완료 (${elapsed}ms)" }
                Outcome.Migrated(migrationId, elapsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            log.warn(e) { "[${options.nodeId}] migration=$migrationId: 마이그레이션 또는 in-lock isApplied() 실패 (${elapsed}ms)" }
            return Outcome.Failed(migrationId, e, elapsed)
        }

        if (inLockOutcome != null) return inLockOutcome

        // 3. Post-skip check — isApplied() 예외는 Failed 로 매핑
        val postSkipApplied = try {
            isApplied()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] migration=$migrationId: post-skip isApplied() 실패" }
            return Outcome.Failed(migrationId, e, System.currentTimeMillis() - started)
        }
        return if (postSkipApplied) {
            log.info { "[${options.nodeId}] migration=$migrationId: post-skip — 다른 인스턴스가 적용 완료" }
            Outcome.AlreadyApplied(migrationId)
        } else {
            log.info { "[${options.nodeId}] migration=$migrationId: skipped (락 미획득 + 마커 미생성)" }
            Outcome.Skipped(migrationId, "락 미획득 within ${options.waitTime}, 마커 미생성")
        }
    }
}

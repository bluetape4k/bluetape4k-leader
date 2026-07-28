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
 * `MigrationGate`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property options example workflow 계약에서 사용하는 속성입니다.
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
     * `runMigration` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

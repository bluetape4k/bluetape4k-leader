package io.bluetape4k.leader.examples.migration

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 3-인스턴스 마이그레이션 게이트 데모 (H2 in-memory).
 *
 * Kubernetes 롤링 배포에서 새 pod 3개가 동시 기동 → 단 1개만 마이그레이션 실행하는 시나리오.
 */
object MigrationGateDemo: KLogging() {

    private object DemoSchemaMarkerTable: Table("demo_schema_markers") {
        val migrationId = varchar("migration_id", 100)
        override val primaryKey = PrimaryKey(migrationId)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 단일 H2 in-memory DB — 3 pod 가 동일 DB 공유 시뮬레이션
        val db = Database.connect(
            url = "jdbc:h2:mem:migration-demo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(db) {
            SchemaUtils.create(DemoSchemaMarkerTable)
        }

        val migrationId = "schema-v3"
        val lockName = "demo-migration-gate"

        log.info { "=== K8s 롤링 배포 시뮬레이션 ===" }
        log.info { "3개 pod 가 동시에 마이그레이션 게이트 진입" }

        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { idx ->
                executor.submit<Outcome> {
                    val gate = MigrationGate(db, MigrationGateOptions(
                        nodeId = "pod-$idx",
                        lockName = lockName,
                        waitTime = 30.seconds,
                        leaseTime = 5.minutes,
                    ))
                    gate.runMigration(
                        migrationId = migrationId,
                        isApplied = {
                            transaction(db) {
                                DemoSchemaMarkerTable.selectAll()
                                    .where { DemoSchemaMarkerTable.migrationId eq migrationId }
                                    .empty().not()
                            }
                        },
                        migration = {
                            log.info { "[pod-$idx] 마이그레이션 SQL 실행 중..." }
                            Thread.sleep(500)
                            transaction(db) {
                                DemoSchemaMarkerTable.insert {
                                    it[DemoSchemaMarkerTable.migrationId] = migrationId
                                }
                            }
                        },
                    )
                }
            }
            val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }

            log.info { "=== 결과 ===" }
            outcomes.forEachIndexed { idx, outcome ->
                log.info { "[pod-${idx + 1}] outcome=$outcome" }
            }
            val migrated = outcomes.count { it is Outcome.Migrated }
            val applied = outcomes.count { it is Outcome.AlreadyApplied }
            log.info { "Migrated=$migrated (기대값 1), AlreadyApplied=$applied (기대값 2)" }
        } finally {
            executor.shutdown()
        }
    }
}

package io.bluetape4k.leader.examples.migration

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.TestInstance
import org.testcontainers.utility.Base58

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractMigrationGateTest {

    companion object: KLogging() {
        @JvmStatic
        fun enableDialects(): List<TestDB> {
            val filter = System.getenv("LEADER_TEST_DB")?.uppercase()
                ?: return listOf(TestDB.H2, TestDB.POSTGRESQL)

            return when (filter) {
                "H2" -> listOf(TestDB.H2)
                "POSTGRESQL", "POSTGRES" -> listOf(TestDB.POSTGRESQL)
                else -> listOf(TestDB.H2, TestDB.POSTGRESQL)
            }
        }
    }

    /** 마이그레이션 완료 마커 테이블 (예제용 — Flyway/Liquibase 대체). */
    object MigrationMarkerTable: Table("migration_marker_example") {
        val migrationId = varchar("migration_id", 100)
        override val primaryKey = PrimaryKey(migrationId)
    }

    protected fun connectDb(testDB: TestDB): Database {
        val db = testDB.db ?: testDB.connect()
        // ExposedJdbcLeaderElector factory 가 leader 테이블 스키마를 자동 생성한다
        ExposedJdbcLeaderElector(db)
        transaction(db) {
            SchemaUtils.create(MigrationMarkerTable)
        }
        return db
    }

    protected fun cleanTables(db: Database) {
        transaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
            MigrationMarkerTable.deleteAll()
        }
    }

    protected fun randomMigrationId(): String = "migration-${Base58.randomString(8)}"
    protected fun randomLockName(): String = "migration-lock-${Base58.randomString(8)}"
}

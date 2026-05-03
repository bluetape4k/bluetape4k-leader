package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * leader-exposed-r2dbc 멀티 DB 테스트의 공통 베이스 클래스.
 *
 * H2, PostgreSQL, MySQL 8 세 가지 DB를 대상으로 파라미터화 테스트를 실행합니다.
 * R2DBC URL은 DB 종류별로 자동 설정됩니다.
 *
 * ## H2 주의사항
 * H2 in-memory에서 `insertIgnore`를 사용하려면 `MODE=MySQL`이 URL에 포함되어야 합니다.
 * 이 베이스 클래스는 H2 URL에 자동으로 MySQL 모드를 적용합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedR2dbcLeaderTest {

    companion object : KLoggingChannel() {

        private val pgContainer: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:15-alpine").also { it.start() }
        }

        private val mysqlContainer: MySQLContainer<*> by lazy {
            MySQLContainer("mysql:8.0").also { it.start() }
        }

        private val dbCache = ConcurrentHashMap<TestR2dbcDB, R2dbcDatabase>()

        /**
         * CI에서 `LEADER_TEST_DB` 환경 변수로 단일 DB를 선택할 수 있습니다.
         * 미설정 시 H2 / PostgreSQL / MySQL 전체 실행.
         *
         * 허용 값: `H2`, `POSTGRESQL` (또는 `POSTGRES`), `MYSQL_V8` (또는 `MYSQL`)
         */
        @JvmStatic
        fun enableDialects(): List<TestR2dbcDB> {
            val filter = System.getenv("LEADER_TEST_DB")?.uppercase()
                ?: return listOf(TestR2dbcDB.H2, TestR2dbcDB.POSTGRESQL, TestR2dbcDB.MYSQL_V8)
            return when (filter) {
                "H2" -> listOf(TestR2dbcDB.H2)
                "POSTGRESQL", "POSTGRES" -> listOf(TestR2dbcDB.POSTGRESQL)
                "MYSQL_V8", "MYSQL" -> listOf(TestR2dbcDB.MYSQL_V8)
                else -> listOf(TestR2dbcDB.H2, TestR2dbcDB.POSTGRESQL, TestR2dbcDB.MYSQL_V8)
            }
        }

        fun r2dbcUrl(testDB: TestR2dbcDB): String = when (testDB) {
            TestR2dbcDB.H2 -> "r2dbc:h2:mem:///leader_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
            TestR2dbcDB.POSTGRESQL -> {
                val c = pgContainer
                "r2dbc:postgresql://${c.host}:${c.getMappedPort(5432)}/${c.databaseName}"
            }
            TestR2dbcDB.MYSQL_V8 -> {
                val c = mysqlContainer
                "r2dbc:mysql://${c.host}:${c.getMappedPort(3306)}/${c.databaseName}"
            }
        }

        fun r2dbcCredentials(testDB: TestR2dbcDB): Pair<String, String> = when (testDB) {
            TestR2dbcDB.H2 -> "" to ""
            TestR2dbcDB.POSTGRESQL -> pgContainer.username to pgContainer.password
            TestR2dbcDB.MYSQL_V8 -> mysqlContainer.username to mysqlContainer.password
        }
    }

    /** testDB에 대한 R2DBC 연결을 반환합니다 (캐시 재사용). */
    protected fun connectDb(testDB: TestR2dbcDB): R2dbcDatabase = dbCache.getOrPut(testDB) {
        val url = r2dbcUrl(testDB)
        val (user, password) = r2dbcCredentials(testDB)
        R2dbcDatabase.connect(url, user = user, password = password)
    }

    /** 테스트 전 스키마 보장 및 테이블 초기화. */
    protected fun setupDb(testDB: TestR2dbcDB): R2dbcDatabase = connectDb(testDB).also { db ->
        runSuspendIO { ExposedR2dbcSchemaInitializer.ensureSchema(db) }
    }

    /** 테스트 간 격리를 위해 리더 선출 테이블 데이터를 모두 삭제합니다. */
    protected suspend fun cleanTables(db: R2dbcDatabase) {
        suspendTransaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
        }
    }

    protected fun randomName(): String = "test-${UUID.randomUUID().toString().take(8)}"
}

/** 테스트 대상 R2DBC DB 종류. */
enum class TestR2dbcDB { H2, POSTGRESQL, MYSQL_V8 }

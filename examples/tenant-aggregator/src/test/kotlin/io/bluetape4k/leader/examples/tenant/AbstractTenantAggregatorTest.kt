package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ConcurrentHashMap

/**
 * tenant-aggregator 멀티 DB 테스트의 공통 베이스.
 *
 * H2 / PostgreSQL 두 가지 DB 를 대상으로 파라미터화 테스트를 실행한다.
 * `LEADER_TEST_DB` 환경 변수로 단일 DB 를 강제할 수 있다 (CI 에서 H2 만 실행 등).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractTenantAggregatorTest {

    companion object: KLogging() {

        private val postgreSQLServer: PostgreSQLServer by lazy {
            PostgreSQLServer.Launcher.postgres
        }

        private val dbCache = ConcurrentHashMap<TestTenantDB, R2dbcDatabase>()

        /**
         * `LEADER_TEST_DB` 환경 변수로 단일 DB 선택. 미설정 시 H2 + PostgreSQL.
         * 허용 값: `H2`, `POSTGRESQL` (또는 `POSTGRES`).
         */
        @JvmStatic
        fun enableDialects(): List<TestTenantDB> {
            val filter = System.getenv("LEADER_TEST_DB")?.uppercase()
                ?: return listOf(TestTenantDB.H2, TestTenantDB.POSTGRESQL)
            return when (filter) {
                "H2" -> listOf(TestTenantDB.H2)
                "POSTGRESQL", "POSTGRES" -> listOf(TestTenantDB.POSTGRESQL)
                "MYSQL_V8", "MYSQL" -> listOf(TestTenantDB.H2)  // 본 모듈은 MySQL 미지원 — H2 로 대체
                else -> listOf(TestTenantDB.H2, TestTenantDB.POSTGRESQL)
            }
        }

        fun r2dbcUrl(testDB: TestTenantDB): String = when (testDB) {
            TestTenantDB.H2 -> "r2dbc:h2:mem:///tenant_${Base58.randomString(6)};MODE=MySQL;DB_CLOSE_DELAY=-1"
            TestTenantDB.POSTGRESQL -> {
                val c = postgreSQLServer
                "r2dbc:postgresql://${c.host}:${c.getMappedPort(5432)}/${c.databaseName}"
            }
        }

        fun r2dbcCredentials(testDB: TestTenantDB): Pair<String?, String?> = when (testDB) {
            TestTenantDB.H2 -> "" to ""
            TestTenantDB.POSTGRESQL -> postgreSQLServer.username to postgreSQLServer.password
        }
    }

    /**
     * testDB 에 대한 R2DBC 연결. PostgreSQL 은 캐시 재사용, H2 는 매번 새 in-memory DB
     * (테스트 격리를 위해).
     */
    protected fun connectDb(testDB: TestTenantDB): R2dbcDatabase = when (testDB) {
        TestTenantDB.H2 -> {
            val url = r2dbcUrl(testDB)
            val (user, password) = r2dbcCredentials(testDB)
            R2dbcDatabase.connect(url, user = user ?: "", password = password ?: "")
        }
        TestTenantDB.POSTGRESQL -> dbCache.getOrPut(testDB) {
            val url = r2dbcUrl(testDB)
            val (user, password) = r2dbcCredentials(testDB)
            R2dbcDatabase.connect(url, user = user ?: "", password = password ?: "")
        }
    }

    protected fun setupDb(testDB: TestTenantDB): R2dbcDatabase = connectDb(testDB).also { db ->
        // ExposedR2dbcSchemaInitializer 는 internal — companion invoke 가 ensureSchema 를 호출하므로
        // throwaway elector 를 한 번 만들어 스키마 보장
        runSuspendIO {
            ExposedR2DbcSuspendLeaderElector(
                db,
                ExposedR2dbcLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(
                        waitTime = 1.seconds,
                        leaseTime = 5.seconds,
                    ),
                ),
            )
        }
    }

    protected suspend fun cleanTables(db: R2dbcDatabase) {
        suspendTransaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
        }
    }

    protected fun randomPrefix(): String = "tenant-test-${Base58.randomString(8)}"
}

/** 테스트 대상 R2DBC DB 종류 (tenant-aggregator 는 H2 + PostgreSQL 만 지원). */
enum class TestTenantDB {
    H2,
    POSTGRESQL,
}

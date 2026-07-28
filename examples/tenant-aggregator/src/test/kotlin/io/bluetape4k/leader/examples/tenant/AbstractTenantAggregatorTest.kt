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
 * `AbstractTenantAggregatorTest`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractTenantAggregatorTest {

    companion object: KLogging() {

        private val postgreSQLServer: PostgreSQLServer by lazy {
            PostgreSQLServer.Launcher.postgres
        }

        private val dbCache = ConcurrentHashMap<TestTenantDB, R2dbcDatabase>()

        /**
         * `enableDialects` 호출은 example workflow 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `connectDb` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

/**
 * `TestTenantDB`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
enum class TestTenantDB {
    H2,
    POSTGRESQL,
}

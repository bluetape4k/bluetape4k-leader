package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * leader-exposed-jdbc 멀티 DB 테스트의 공통 베이스 클래스.
 *
 * H2, PostgreSQL, MySQL_V8 세 가지 DB를 대상으로 파라미터화 테스트를 실행합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedJdbcLeaderTest {

    companion object : KLogging() {
        @JvmStatic
        fun enableDialects(): List<TestDB> = listOf(
            TestDB.H2,
            TestDB.POSTGRESQL,
            TestDB.MYSQL_V8,
        )
    }

    /** testDB에 대한 DB 연결을 반환합니다 (캐시 재사용). */
    protected fun connectDb(testDB: TestDB): Database {
        val db = testDB.db ?: testDB.connect()
        ExposedJdbcSchemaInitializer.ensureSchema(db)
        return db
    }

    /** 테스트 간 격리를 위해 리더 선출 테이블 데이터를 모두 삭제합니다. */
    protected fun cleanTables(db: Database) {
        transaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
        }
    }

    protected fun randomName(): String = "test-${UUID.randomUUID().toString().take(8)}"
}

package io.bluetape4k.leader.exposed

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb
import io.bluetape4k.exposed.tests.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ExposedLeaderSchemaTest : AbstractExposedTableTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `allTables에 3개 테이블이 포함되어 있다`(testDB: TestDB) {
        ExposedLeaderSchema.allTables.size shouldBeEqualTo 3
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `allTables로 SchemaUtils createMissingTablesAndColumns 실행이 성공한다`(testDB: TestDB) {
        // withTables을 사용해 create/drop을 처리
        withTables(testDB, *ExposedLeaderSchema.allTables) {
            // 3개 테이블 모두 존재해야 함
            io.bluetape4k.leader.exposed.tables.LeaderLockTable.exists() shouldBeEqualTo true
            io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable.exists() shouldBeEqualTo true
            io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable.exists() shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `allTables로 SchemaUtils drop 실행이 성공한다`(testDB: TestDB) {
        // [HIGH-3] Array<Table>이므로 * spread 연산자로 직접 전달 (List 변환 없이 O(1))
        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
            SchemaUtils.drop(*ExposedLeaderSchema.allTables)

            // 테이블이 삭제되었는지 확인
            io.bluetape4k.leader.exposed.tables.LeaderLockTable.exists() shouldBeEqualTo false
        }
    }
}

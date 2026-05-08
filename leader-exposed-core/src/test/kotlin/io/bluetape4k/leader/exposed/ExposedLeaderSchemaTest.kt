package io.bluetape4k.leader.exposed

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.assertions.shouldBeEqualTo
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
        withTables(testDB, *ExposedLeaderSchema.allTables) {
            LeaderLockTable.exists() shouldBeEqualTo true
            LeaderGroupLockTable.exists() shouldBeEqualTo true
            LeaderLockHistoryTable.exists() shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `allTables로 SchemaUtils drop 실행이 성공한다`(testDB: TestDB) {
        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
            SchemaUtils.drop(*ExposedLeaderSchema.allTables)

            LeaderLockTable.exists() shouldBeEqualTo false
            LeaderGroupLockTable.exists() shouldBeEqualTo false
            LeaderLockHistoryTable.exists() shouldBeEqualTo false
        }
    }
}

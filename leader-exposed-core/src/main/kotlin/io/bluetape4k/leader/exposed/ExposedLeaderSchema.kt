package io.bluetape4k.leader.exposed

import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.core.Table

/**
 * leader-exposed-core가 관리하는 모든 테이블의 집합.
 *
 * JDBC/R2DBC 구현 모듈의 초기화 코드에서 아래와 같이 일괄 스키마 생성:
 * ```kotlin
 * SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
 * ```
 *
 * [allTables]는 `Array<Table>`이므로 `*` spread 연산자로 vararg에 O(1) 전달 가능.
 */
object ExposedLeaderSchema {

    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
}

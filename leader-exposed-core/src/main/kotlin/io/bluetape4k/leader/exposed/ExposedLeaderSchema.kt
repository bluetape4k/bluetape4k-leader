package io.bluetape4k.leader.exposed

import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.core.Table

/**
 * The set of all tables managed by leader-exposed-core.
 *
 * Use the following pattern in JDBC/R2DBC implementation module initializers to create all schemas at once:
 * ```kotlin
 * SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
 * ```
 *
 * [allTables] is an `Array<Table>`, so it can be passed to vararg parameters via the `*` spread operator in O(1).
 */
object ExposedLeaderSchema {

    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
}

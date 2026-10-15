package io.bluetape4k.leader.exposed

import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.core.Table

/**
 * `ExposedLeaderSchema`는 단일 lock, group lock, history 테이블을 Exposed 생성 순서로 묶어 제공합니다.
 *
 * @property allTables 애플리케이션이 한 번에 생성할 수 있는 단일 lock, group lock, history 테이블의 순서 있는 목록입니다.
 */
object ExposedLeaderSchema {

    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
}

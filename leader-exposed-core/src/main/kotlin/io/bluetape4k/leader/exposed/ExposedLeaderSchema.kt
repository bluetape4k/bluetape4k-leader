package io.bluetape4k.leader.exposed

import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.core.Table

/**
 * `ExposedLeaderSchema`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property allTables Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
object ExposedLeaderSchema {

    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
}

package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `LeaderGroupLockTable`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
object LeaderGroupLockTable : Table(GROUP_LOCK_TABLE_NAME) {

    /**
     * `lockName` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /**
     * `slot` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val slot = integer("slot")

    /**
     * `lockOwner` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /**
     * `token` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val token = varchar("token", TOKEN_LENGTH)

    /**
     * `lockedAt` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockedAt = timestamp("locked_at")

    /**
     * `lockedUntil` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName, slot)
}

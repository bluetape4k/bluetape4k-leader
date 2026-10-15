package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `LeaderGroupLockTable`는 `(lockName, slot)`별 group lock owner, token, lease 시각을 저장하는 Exposed 테이블입니다.
 *
 */
object LeaderGroupLockTable: Table(GROUP_LOCK_TABLE_NAME) {

    /** group lock 이름입니다. `slot`과 함께 기본 키를 구성하며 최대 255자입니다. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 같은 group lock 안에서 한 leadership 자리를 구분하는 슬롯 번호입니다. */
    val slot = integer("slot")

    /** 선택적으로 기록하는 owner 식별자입니다. 미지정하면 `NULL`이며 최대 255자입니다. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** 현재 슬롯 획득자의 token입니다. 해제·연장 시 `lockName`과 `slot`과 함께 소유권을 확인하며 최대 36자입니다. */
    val token = varchar("token", TOKEN_LENGTH)

    /** 현재 슬롯 lease를 획득한 시각입니다. */
    val lockedAt = timestamp("locked_at")

    /** 현재 슬롯 lease가 만료되는 시각입니다. 이 시각이 지나야 해당 슬롯을 다시 획득할 수 있습니다. */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName, slot)
}

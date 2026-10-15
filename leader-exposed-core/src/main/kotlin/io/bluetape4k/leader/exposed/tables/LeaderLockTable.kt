package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `LeaderLockTable`는 단일 leader lock의 이름, owner, token, lease 시각을 저장하는 Exposed 테이블입니다.
 *
 */
object LeaderLockTable: Table(LOCK_TABLE_NAME) {

    /** lock 이름이며 기본 키입니다. 최대 255자이고 이 값으로 같은 lock 행을 갱신합니다. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 선택적으로 기록하는 owner 식별자입니다. 미지정하면 `NULL`이며 최대 255자입니다. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** 현재 획득자의 token입니다. 해제·연장 시 `lockName`과 함께 소유권을 확인하며 최대 36자입니다. */
    val token = varchar("token", TOKEN_LENGTH)

    /** 현재 lease를 획득한 시각입니다. */
    val lockedAt = timestamp("locked_at")

    /** 현재 lease가 만료되는 시각입니다. 이 시각이 지나야 같은 `lockName`을 다시 획득할 수 있습니다. */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName)
}

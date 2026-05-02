package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 단일 리더 락 테이블.
 *
 * - [lockName]이 PK — INSERT 충돌로 중복 획득 자연스럽게 방지
 * - [token]은 fencing token (UUID). 락 해제 시 `WHERE token = ?` 조건으로 zombie unlock 방지
 * - [lockedUntil]은 TTL 만료 시각. `locked_until < NOW()` 조건으로 stale lock 재획득 가능
 */
object LeaderLockTable : Table(LOCK_TABLE_NAME) {

    /** 락 식별자 (PK). 영숫자, 하이픈, 언더스코어, 콜론 허용 */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 락 보유자 식별자 (hostname + PID 등). null이면 미사용 슬롯 */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** fencing token — UUID. 동일 락의 구세대 holder가 해제 시도 시 거부 */
    val token = varchar("token", TOKEN_LENGTH)

    /** 락 획득 시각 (UTC) */
    val lockedAt = timestamp("locked_at")

    /** 락 만료 시각 (UTC). leaseTime 기준. 이 시각 이후 stale로 판정 */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName)
}

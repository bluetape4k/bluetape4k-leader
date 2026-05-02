package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 그룹 리더 락 테이블 (세마포어 기반 멀티 리더).
 *
 * - `(lockName, slot)` 복합 PK — 동일 그룹에서 최대 N개의 리더를 허용
 * - [slot]은 0-based 슬롯 번호. MongoDB의 `${lockName}:slot:N` 패턴과 동일한 의미
 * - [token]은 fencing token. 슬롯별로 독립적인 UUID 발급
 * - [lockedUntil]은 슬롯 TTL 만료 시각. `locked_until < NOW()` 조건으로 재획득 가능
 */
object LeaderGroupLockTable : Table(GROUP_LOCK_TABLE_NAME) {

    /** 그룹 락 식별자. 복합 PK 일부 */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 슬롯 번호 (0-based). maxLeaders 설정 범위 내 값. 복합 PK 일부 */
    val slot = integer("slot")

    /** 락 보유자 식별자. null이면 미사용 슬롯 */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** fencing token — UUID. 슬롯별 독립 발급 */
    val token = varchar("token", TOKEN_LENGTH)

    /** 락 획득 시각 (UTC) */
    val lockedAt = timestamp("locked_at")

    /** 락 만료 시각 (UTC). 이 시각 이후 해당 슬롯은 재획득 가능 */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName, slot)
}

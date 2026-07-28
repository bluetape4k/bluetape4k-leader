package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.STATUS_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `LeaderLockHistoryTable`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
object LeaderLockHistoryTable : Table(LOCK_HISTORY_TABLE_NAME) {

    /**
     * `id` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val id = long("id").autoIncrement()

    /**
     * `lockName` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /**
     * `lockOwner` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /**
     * `token` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val token = varchar("token", TOKEN_LENGTH)

    /**
     * `slot` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val slot = integer("slot").nullable()

    /**
     * `lockedUntil` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val lockedUntil = timestamp("locked_until")

    /**
     * `status` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val status = varchar("status", STATUS_LENGTH)

    /**
     * `startedAt` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val startedAt = timestamp("started_at")

    /**
     * `finishedAt` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val finishedAt = timestamp("finished_at").nullable()

    /**
     * `durationMs` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val durationMs = long("duration_ms").nullable()

    // ── Audit contract columns (Issue #50) ────────────────────────────────

    /**
     * `errorType` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val errorType = varchar("error_type", 255).nullable()

    /**
     * `errorMessage` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val errorMessage = varchar("error_message", 512).nullable()

    /**
     * `kind` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val kind = varchar("kind", 32).nullable()

    /**
     * `participantId` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val participantId = varchar("participant_id", 255).nullable()

    /**
     * `metadata` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val metadata = text("metadata").nullable()

    /**
     * `slotId` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val slotId = varchar("slot_id", 255).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_history_lock_started", isUnique = false, lockName, startedAt)
        index(customIndexName = "idx_history_token", isUnique = false, token)
    }
}

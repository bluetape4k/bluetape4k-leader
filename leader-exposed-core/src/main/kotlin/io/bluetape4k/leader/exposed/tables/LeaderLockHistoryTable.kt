package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.STATUS_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import io.bluetape4k.leader.history.LeaderHistoryStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `LeaderLockHistoryTable`는 leader lock 획득·완료·실패와 실행 시간, 오류, metadata를 보존하는 Exposed 테이블입니다.
 *
 */
object LeaderLockHistoryTable: Table(LOCK_HISTORY_TABLE_NAME) {

    /** history 행의 자동 증가 기본 키입니다. 후속 상태 갱신에서 먼저 사용하는 식별자입니다. */
    val id = long("id").autoIncrement()

    /** 이력이 가리키는 lock 이름입니다. 최대 255자이며 `(id, token)` 또는 `(lockName, token)` 조회에 사용합니다. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 획득 시 지정한 owner 식별자입니다. 지정하지 않으면 `NULL`이며 최대 255자입니다. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** 이력 행과 lock 소유권을 연결하는 token입니다. 후속 갱신의 조건으로 사용되며 최대 36자입니다. 로그에 원문을 남기지 마세요. */
    val token = varchar("token", TOKEN_LENGTH)

    /** group election에서 사용한 슬롯 번호입니다. 단일 election 이력은 `NULL`입니다. */
    val slot = integer("slot").nullable()

    /** 획득 시점에 계산한 lease 만료 시각입니다. */
    val lockedUntil = timestamp("locked_until")

    /** 이력의 현재 상태를 `LeaderHistoryStatus` 이름으로 저장합니다. 최대 20자입니다. */
    val status = enumerationByName<LeaderHistoryStatus>("status", STATUS_LENGTH)

    /** lock 작업을 시작한 시각이며 보존 기간 삭제의 기준입니다. */
    val startedAt = timestamp("started_at")

    /** 작업이 끝난 시각입니다. 실행 중이거나 종료 기록이 없으면 `NULL`입니다. */
    val finishedAt = timestamp("finished_at").nullable()

    /** 시작부터 완료까지의 경과 시간(밀리초)입니다. 아직 완료되지 않았으면 `NULL`입니다. */
    val durationMs = long("duration_ms").nullable()

    // ── Audit contract columns (Issue #50) ────────────────────────────────

    /** 실패 시 기록한 예외 타입 이름입니다. 성공했거나 타입을 알 수 없으면 `NULL`이며 최대 255자입니다. */
    val errorType = varchar("error_type", 255).nullable()

    /** 실패 시 기록한 오류 메시지입니다. 성공했거나 메시지가 없으면 `NULL`이며 최대 512자입니다. */
    val errorMessage = varchar("error_message", 512).nullable()

    /** 단일/group election을 구분하는 annotation 종류입니다. 값이 없는 legacy 이력은 `NULL`입니다. */
    val kind = enumerationByName<LockIdentity.AnnotationKind>("kind", 32).nullable()

    /** 이 작업을 실행한 participant 또는 node 식별자입니다. 없으면 `NULL`이며 최대 255자입니다. */
    val participantId = varchar("participant_id", 255).nullable()

    /** recorder가 정제한 caller metadata를 JSON object로 저장합니다. metadata가 없으면 `NULL`이며 민감정보는 넣지 마세요. */
    val metadata = text("metadata").nullable()

    /** group election 슬롯의 외부 식별자입니다. 단일 election 또는 식별자 미제공 시 `NULL`이며 최대 255자입니다. */
    val slotId = varchar("slot_id", 255).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_history_lock_started", isUnique = false, lockName, startedAt)
        index(customIndexName = "idx_history_token", isUnique = false, token)
    }
}

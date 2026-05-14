package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.STATUS_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 리더 선출 이력 테이블.
 *
 * 상태 라이프사이클: [HistoryStatus.ACQUIRED] → [HistoryStatus.COMPLETED] | [HistoryStatus.FAILED] | [HistoryStatus.EXPIRED]
 *
 * - [token]은 NOT NULL — 락 획득 시점의 fencing token.
 *   EXPIRED 전환 시 `WHERE token = ?` 조건으로 정확한 이력 레코드 매칭.
 * - [slot]은 그룹 락 전용. 단일 리더 락은 null.
 * - [lockedUntil]은 EXPIRED 판정 기준 — `lockedUntil < NOW()` 이면 만료로 전환.
 *
 * TTL 정책: 30일 초과 레코드는 정기 배치로 삭제.
 * `DELETE WHERE started_at < :cutoff` — DB-native INTERVAL 대신 Kotlin [java.time.Instant] 파라미터 바인딩 사용
 * (H2/PostgreSQL/MySQL INTERVAL 문법 불일치 회피).
 */
object LeaderLockHistoryTable : Table(LOCK_HISTORY_TABLE_NAME) {

    /** AUTO_INCREMENT PK */
    val id = long("id").autoIncrement()

    /** 락 식별자 */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** 락 보유자 식별자. null 허용 */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** fencing token — 락 획득 시점의 UUID. EXPIRED 전환 매칭에 사용 */
    val token = varchar("token", TOKEN_LENGTH)

    /** 그룹 락 슬롯 번호. 단일 리더 락은 null */
    val slot = integer("slot").nullable()

    /** 이 획득의 만료 시각 (UTC). EXPIRED 판정 기준 */
    val lockedUntil = timestamp("locked_until")

    /** 이력 상태. [HistoryStatus] enum의 name 값 저장 */
    val status = varchar("status", STATUS_LENGTH)

    /** 락 획득(ACQUIRED) 시각 (UTC) */
    val startedAt = timestamp("started_at")

    /** 리더 action 완료 시각. ACQUIRED 상태에서는 null */
    val finishedAt = timestamp("finished_at").nullable()

    /** 리더 action 수행 소요 시간 (밀리초). ACQUIRED 상태에서는 null */
    val durationMs = long("duration_ms").nullable()

    // ── Audit contract columns (Issue #50) ────────────────────────────────

    /** Fully-qualified class name of the thrown exception. null when action succeeded. */
    val errorType = varchar("error_type", 255).nullable()

    /** Sanitized exception message, truncated to 512 UTF-8 bytes. null when action succeeded. */
    val errorMessage = varchar("error_message", 512).nullable()

    /**
     * [io.bluetape4k.leader.LockIdentity.AnnotationKind] name (SINGLE / GROUP).
     * Stored as VARCHAR to avoid ordinal-dependency (D3).
     */
    val kind = varchar("kind", 32).nullable()

    /** Node/instance identifier (hostname, pod name, etc.). */
    val participantId = varchar("participant_id", 255).nullable()

    /** JSON-serialized metadata map. null when no metadata was supplied. */
    val metadata = text("metadata").nullable()

    /**
     * Slot identifier for group elections stored as VARCHAR(255).
     * Redisson permitId is UUID-shaped — cannot be stored as Int without NFE (H4).
     * When non-null and parseable as Int, the legacy [slot] column is also populated
     * for backward compatibility.
     */
    val slotId = varchar("slot_id", 255).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_history_lock_started", isUnique = false, lockName, startedAt)
        index(customIndexName = "idx_history_token", isUnique = false, token)
    }
}

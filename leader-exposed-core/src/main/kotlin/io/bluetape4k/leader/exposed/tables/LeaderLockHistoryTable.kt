package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.STATUS_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Leader election history table.
 *
 * Status lifecycle: [io.bluetape4k.leader.history.LeaderHistoryStatus.ACQUIRED] → [io.bluetape4k.leader.history.LeaderHistoryStatus.COMPLETED] | [io.bluetape4k.leader.history.LeaderHistoryStatus.FAILED] | [io.bluetape4k.leader.history.LeaderHistoryStatus.EXPIRED]
 *
 * - [token] is NOT NULL — the fencing token at the time of lock acquisition.
 *   Used with `WHERE token = ?` when transitioning to EXPIRED for accurate history record matching.
 * - [slot] is exclusive to group locks. Null for single-leader locks.
 * - [lockedUntil] is the EXPIRED judgment threshold — transitions to expired when `lockedUntil < NOW()`.
 *
 * TTL policy: Records older than 30 days are deleted by a periodic batch job.
 * Uses Kotlin [java.time.Instant] parameter binding instead of DB-native INTERVAL
 * to avoid syntax incompatibilities between H2/PostgreSQL/MySQL.
 */
object LeaderLockHistoryTable : Table(LOCK_HISTORY_TABLE_NAME) {

    /** AUTO_INCREMENT PK. */
    val id = long("id").autoIncrement()

    /** Lock identifier. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** Lock holder identifier. Nullable. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** Fencing token — UUID at the time of lock acquisition. Used for EXPIRED transition matching. */
    val token = varchar("token", TOKEN_LENGTH)

    /** Group lock slot number. Null for single-leader locks. */
    val slot = integer("slot").nullable()

    /** Expiry timestamp for this acquisition (UTC). Used as the EXPIRED judgment threshold. */
    val lockedUntil = timestamp("locked_until")

    /** History status. Stores the name of the [io.bluetape4k.leader.history.LeaderHistoryStatus] enum value. */
    val status = varchar("status", STATUS_LENGTH)

    /** Timestamp when the lock was acquired (ACQUIRED state) (UTC). */
    val startedAt = timestamp("started_at")

    /** Timestamp when the leader action completed. Null while in ACQUIRED state. */
    val finishedAt = timestamp("finished_at").nullable()

    /** Duration of the leader action in milliseconds. Null while in ACQUIRED state. */
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

package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Single-leader lock table.
 *
 * - [lockName] is the PK — duplicate acquisition is naturally prevented by INSERT conflicts.
 * - [token] is a fencing token (UUID). The `WHERE token = ?` condition on unlock prevents zombie unlocks.
 * - [lockedUntil] is the TTL expiry timestamp. The `locked_until < NOW()` condition allows re-acquisition of stale locks.
 */
object LeaderLockTable : Table(LOCK_TABLE_NAME) {

    /** Lock identifier (PK). Allows alphanumerics, hyphens, underscores, and colons. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** Lock holder identifier (hostname + PID, etc.). Null means unused slot. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** Fencing token — UUID. Rejects unlock attempts from an older holder of the same lock. */
    val token = varchar("token", TOKEN_LENGTH)

    /** Timestamp when the lock was acquired (UTC). */
    val lockedAt = timestamp("locked_at")

    /** Lock expiry timestamp (UTC). Based on leaseTime. Considered stale after this time. */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName)
}

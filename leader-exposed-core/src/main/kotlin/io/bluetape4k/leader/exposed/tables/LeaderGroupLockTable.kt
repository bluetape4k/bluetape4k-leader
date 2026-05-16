package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Group leader lock table (semaphore-based multi-leader).
 *
 * - `(lockName, slot)` composite PK — allows up to N simultaneous leaders in the same group.
 * - [slot] is a 0-based slot number, equivalent to MongoDB's `${lockName}:slot:N` pattern.
 * - [token] is a fencing token with an independent UUID issued per slot.
 * - [lockedUntil] is the slot TTL expiry timestamp. Re-acquisition is possible via `locked_until < NOW()`.
 */
object LeaderGroupLockTable : Table(GROUP_LOCK_TABLE_NAME) {

    /** Group lock identifier. Part of the composite PK. */
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)

    /** Slot number (0-based). Value within the maxLeaders range. Part of the composite PK. */
    val slot = integer("slot")

    /** Lock holder identifier. Null means unused slot. */
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()

    /** Fencing token — UUID. Issued independently per slot. */
    val token = varchar("token", TOKEN_LENGTH)

    /** Timestamp when the lock was acquired (UTC). */
    val lockedAt = timestamp("locked_at")

    /** Lock expiry timestamp (UTC). The slot can be re-acquired after this time. */
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName, slot)
}

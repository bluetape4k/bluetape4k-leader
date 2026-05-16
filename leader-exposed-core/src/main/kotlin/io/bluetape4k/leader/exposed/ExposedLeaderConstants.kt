package io.bluetape4k.leader.exposed

/**
 * Shared constants for the leader-exposed-core module.
 * Centralizes table names and column length constraints.
 */
object ExposedLeaderConstants {

    /** Table name for single-leader locks. */
    const val LOCK_TABLE_NAME = "bluetape4k_leader_locks"

    /** Table name for group leader locks (semaphore-based multi-leader). */
    const val GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"

    /** Table name for leader election history. */
    const val LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"

    /** Maximum length of the lockName column. */
    const val LOCK_NAME_LENGTH = 255

    /** Maximum length of the lockOwner column. */
    const val LOCK_OWNER_LENGTH = 255

    /** Length of the fencing token (UUID) column — standard UUID is 36 characters. */
    const val TOKEN_LENGTH = 36

    /** Maximum length of the history status column. */
    const val STATUS_LENGTH = 20
}

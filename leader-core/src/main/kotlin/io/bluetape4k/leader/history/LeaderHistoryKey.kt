package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Composite key returned by [LeaderHistorySink.recordAcquired] and passed to
 * subsequent [LeaderHistorySink.recordCompleted] / [LeaderHistorySink.recordFailed] calls.
 *
 * ## Behavior / Contract
 * Sinks may use different update strategies depending on which fields are populated:
 *
 * | [id] | [historyId] | Strategy |
 * |------|-------------|----------|
 * | non-null | any | Primary-key update (JDBC `UPDATE … WHERE id = ?`) |
 * | null | non-null | Natural-key update (`WHERE historyId = ?`) |
 * | null | null | Natural-key update (`WHERE lockName = ? AND token = ?`) |
 *
 * - [lockName] and [token] are always required and validated non-blank.
 * - [slotId] is populated for group elections (Redisson `permitId` or Lettuce slot token);
 *   sinks store it in a `VARCHAR(255)` column to avoid `toInt()` NFE on UUID-shaped values.
 *
 * ## Example
 * ```kotlin
 * val key: LeaderHistoryKey? = sink.recordAcquired(record)
 * val fallbackKey = key ?: LeaderHistoryKey(lockName = record.lockName, token = record.token)
 * ```
 */
data class LeaderHistoryKey(
    /** Auto-increment or surrogate primary key; null when the sink does not use numeric PKs. */
    val id: Long? = null,
    /** UUID or opaque string primary key; null when the sink uses [id]. */
    val historyId: String? = null,
    /** Lock name — always required. */
    val lockName: String,
    /** Live lock-release credential — always required. */
    val token: String,
    /** Slot identifier for group elections; stored as VARCHAR to avoid NFE on UUID-shaped values. */
    val slotId: String? = null,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        token.requireNotBlank("token")
    }

    // Redact token to prevent credential leakage via log statements that interpolate this key
    override fun toString(): String =
        "LeaderHistoryKey(id=$id, historyId=$historyId, lockName=$lockName, token=***, slotId=$slotId)"

    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
}

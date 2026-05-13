package io.bluetape4k.leader.exposed.jdbc.history

import io.bluetape4k.leader.exposed.history.MetadataJsonCodec
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Exposed JDBC implementation of [LeaderHistorySink].
 *
 * Persists leader-lock lifecycle events into [LeaderLockHistoryTable] using
 * synchronous JDBC transactions.  Intended to be wrapped by
 * [io.bluetape4k.leader.history.SafeLeaderHistoryRecorder], which absorbs
 * exceptions so that a storage failure never affects the lock action result.
 *
 * ## Behavior / Contract
 * - [recordAcquired] inserts a row and returns [LeaderHistoryKey] with the
 *   auto-generated `id`.
 * - [recordCompleted] and [recordFailed] use a three-strategy update:
 *   1. `WHERE id = ?` when [LeaderHistoryKey.id] is non-null.
 *   2. `WHERE historyId = ?` when [LeaderHistoryKey.historyId] is non-null.
 *   3. `WHERE lockName = ? AND token = ?` as a null-key fallback.
 * - [deleteOlderThan] uses `deleteWhere` with a row-count [limit] for bounded
 *   retention batches.
 *
 * ## Security / Trust Boundary
 * The `token` column stores the live lock-release credential.  Database read
 * access to this table must be restricted to the same trust boundary as the
 * lock backend.  Do not expose this table via public APIs or logs at INFO level
 * or above.
 *
 * @param database Exposed [Database] instance.
 */
class ExposedLeaderHistorySink(
    private val database: Database,
) : LeaderHistorySink {

    companion object : KLogging()

    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        val id = transaction(database) {
            LeaderLockHistoryTable.insert {
                it[lockName] = record.lockName
                it[token] = record.token
                it[lockedUntil] = record.lockedUntil
                it[status] = LeaderHistoryStatus.ACQUIRED.name
                it[startedAt] = record.acquiredAt
                it[kind] = record.kind.name
                it[participantId] = record.nodeId
                it[slotId] = record.slotId
                it[slot] = record.slotId?.toIntOrNull()
                it[metadata] = MetadataJsonCodec.encode(record.metadata)
            }[LeaderLockHistoryTable.id]
        }
        return LeaderHistoryKey(id = id, lockName = record.lockName, token = record.token, slotId = record.slotId)
    }

    override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        updateFinished(key, finishedAt, durationMs, LeaderHistoryStatus.COMPLETED, null, null)
    }

    override fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) {
        updateFinished(key, finishedAt, durationMs, LeaderHistoryStatus.FAILED, errorType, errorMessage)
    }

    /**
     * Deletes records with [LeaderLockHistoryTable.startedAt] before [cutoff],
     * processing at most [limit] rows per call.
     *
     * @return number of rows deleted.
     */
    override fun deleteOlderThan(cutoff: Instant, limit: Int): Int =
        transaction(database) {
            LeaderLockHistoryTable.deleteWhere(limit = limit) {
                LeaderLockHistoryTable.startedAt less cutoff
            }
        }

    private fun updateFinished(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        status: LeaderHistoryStatus,
        errorType: String?,
        errorMessage: String?,
    ) {
        val updated = transaction(database) {
            when {
                key.id != null -> LeaderLockHistoryTable.update(
                    where = { LeaderLockHistoryTable.id eq key.id }
                ) { row ->
                    row[LeaderLockHistoryTable.status] = status.name
                    row[LeaderLockHistoryTable.finishedAt] = finishedAt
                    row[LeaderLockHistoryTable.durationMs] = durationMs
                    row[LeaderLockHistoryTable.errorType] = errorType
                    row[LeaderLockHistoryTable.errorMessage] = errorMessage
                }

                key.historyId != null -> LeaderLockHistoryTable.update(
                    where = { LeaderLockHistoryTable.token eq key.token }
                ) { row ->
                    row[LeaderLockHistoryTable.status] = status.name
                    row[LeaderLockHistoryTable.finishedAt] = finishedAt
                    row[LeaderLockHistoryTable.durationMs] = durationMs
                    row[LeaderLockHistoryTable.errorType] = errorType
                    row[LeaderLockHistoryTable.errorMessage] = errorMessage
                }

                else -> LeaderLockHistoryTable.update(
                    where = {
                        (LeaderLockHistoryTable.lockName eq key.lockName) and
                            (LeaderLockHistoryTable.token eq key.token)
                    }
                ) { row ->
                    row[LeaderLockHistoryTable.status] = status.name
                    row[LeaderLockHistoryTable.finishedAt] = finishedAt
                    row[LeaderLockHistoryTable.durationMs] = durationMs
                    row[LeaderLockHistoryTable.errorType] = errorType
                    row[LeaderLockHistoryTable.errorMessage] = errorMessage
                }
            }
        }
        if (updated == 0) {
            log.warn { "No history row updated for key=$key status=$status — possible duplicate or missing record" }
        }
    }
}

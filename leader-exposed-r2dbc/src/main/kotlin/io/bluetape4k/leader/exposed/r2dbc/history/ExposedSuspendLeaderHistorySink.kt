package io.bluetape4k.leader.exposed.r2dbc.history

import io.bluetape4k.leader.exposed.history.MetadataJsonCodec
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant

/**
 * `ExposedSuspendLeaderHistorySink`는 suspend transaction으로 leader history 행을 기록하고 완료·실패 상태를 갱신합니다.
 *
 * @property database suspend transaction으로 history 행을 쓰고 정리할 Exposed R2DBC 데이터베이스입니다.
 */
class ExposedSuspendLeaderHistorySink(
    private val database: R2dbcDatabase,
): SuspendLeaderHistorySink {

    companion object: KLoggingChannel()

    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
        val id = suspendTransaction(database) {
            LeaderLockHistoryTable.insert {
                it[lockName] = record.lockName
                it[token] = record.token
                it[lockedUntil] = record.lockedUntil
                it[status] = LeaderHistoryStatus.ACQUIRED
                it[startedAt] = record.acquiredAt
                it[kind] = record.kind
                it[participantId] = record.nodeId
                it[slotId] = record.slotId
                it[slot] = record.slotId?.toIntOrNull()
                it[metadata] = MetadataJsonCodec.encode(record.metadata)
            }[LeaderLockHistoryTable.id]
        }
        return LeaderHistoryKey(id = id, lockName = record.lockName, token = record.token, slotId = record.slotId)
    }

    override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        updateFinished(key, finishedAt, durationMs, LeaderHistoryStatus.COMPLETED, null, null)
    }

    override suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) {
        updateFinished(key, finishedAt, durationMs, LeaderHistoryStatus.FAILED, errorType, errorMessage)
    }

    /**
     * [cutoff]보다 이전에 시작된 history 행을 최대 [limit]개 삭제하고 삭제한 행 수를 반환합니다.
     * 삭제는 suspend transaction에서 수행됩니다.
     */
    override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int =
        suspendTransaction(database) {
            LeaderLockHistoryTable.deleteWhere(limit = limit) {
                LeaderLockHistoryTable.startedAt less cutoff
            }
        }

    private suspend fun updateFinished(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        status: LeaderHistoryStatus,
        errorType: String?,
        errorMessage: String?,
    ) {
        val keyId = key.id
        val updated = suspendTransaction(database) {
            val where = if (keyId != null) {
                { (LeaderLockHistoryTable.id eq keyId) and (LeaderLockHistoryTable.token eq key.token) }
            } else {
                { (LeaderLockHistoryTable.lockName eq key.lockName) and (LeaderLockHistoryTable.token eq key.token) }
            }
            LeaderLockHistoryTable.update(where = where) { row ->
                row[LeaderLockHistoryTable.status] = status
                row[LeaderLockHistoryTable.finishedAt] = finishedAt
                row[LeaderLockHistoryTable.durationMs] = durationMs
                row[LeaderLockHistoryTable.errorType] = errorType
                row[LeaderLockHistoryTable.errorMessage] = errorMessage
            }
        }
        if (updated == 0) {
            log.warn { "No history row updated for key=$key status=$status — possible duplicate or missing record" }
        }
    }
}

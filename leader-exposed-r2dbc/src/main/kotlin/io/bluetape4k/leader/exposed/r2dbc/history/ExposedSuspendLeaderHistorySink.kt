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
 * `ExposedSuspendLeaderHistorySink`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property database Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedSuspendLeaderHistorySink(
    private val database: R2dbcDatabase,
) : SuspendLeaderHistorySink {

    companion object : KLoggingChannel()

    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        val id = suspendTransaction(database) {
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
     * `deleteOlderThan` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
                row[LeaderLockHistoryTable.status] = status.name
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

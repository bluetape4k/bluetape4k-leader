package io.bluetape4k.leader.exposed.jdbc.history

import io.bluetape4k.leader.exposed.history.MetadataJsonCodec
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderHistoryStatus
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
 * `ExposedLeaderHistorySink`는 JDBC transaction으로 leader history 행을 기록하고 완료·실패 상태를 갱신합니다.
 *
 * @property database history 테이블에 획득·완료·실패 행을 쓰고 정리할 Exposed JDBC 데이터베이스입니다.
 */
class ExposedLeaderHistorySink(
    private val database: Database,
): LeaderHistorySink {

    companion object: KLogging()

    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
        val id = transaction(database) {
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
        updateFinished(
            key,
            finishedAt,
            durationMs,
            LeaderHistoryStatus.FAILED,
            errorType,
            errorMessage
        )
    }

    /**
     * [cutoff]보다 이전에 시작된 history 행을 최대 [limit]개 삭제하고 삭제한 행 수를 반환합니다.
     * 삭제는 하나의 JDBC transaction에서 수행되며, 음수 limit의 검증은 Exposed에 위임됩니다.
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
        val keyId = key.id
        val updated = transaction(database) {
            val whereOp = if (keyId != null) {
                { (LeaderLockHistoryTable.id eq keyId) and (LeaderLockHistoryTable.token eq key.token) }
            } else {
                { (LeaderLockHistoryTable.lockName eq key.lockName) and (LeaderLockHistoryTable.token eq key.token) }
            }
            LeaderLockHistoryTable.update(where = whereOp) { row ->
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

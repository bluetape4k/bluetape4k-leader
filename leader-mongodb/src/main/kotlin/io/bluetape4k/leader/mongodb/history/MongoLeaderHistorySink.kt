package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.time.Instant
import java.util.UUID

/**
 * `MongoLeaderHistorySink`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property database MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property config MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoLeaderHistorySink(
    private val database: MongoDatabase,
    private val config: MongoHistoryConfig = MongoHistoryConfig(),
) : SuspendLeaderHistorySink {

    companion object : KLoggingChannel() {
        private const val FIELD_HISTORY_ID = "historyId"
        private const val FIELD_LOCK_NAME = "lockName"
        private const val FIELD_TOKEN = "token"
        private const val FIELD_STATUS = "status"
        private const val FIELD_STARTED_AT = "startedAt"
        private const val FIELD_LOCKED_UNTIL = "lockedUntil"
        private const val FIELD_FINISHED_AT = "finishedAt"
        private const val FIELD_DURATION_MS = "durationMs"
        private const val FIELD_KIND = "kind"
        private const val FIELD_PARTICIPANT_ID = "participantId"
        private const val FIELD_SLOT_ID = "slotId"
        private const val FIELD_ERROR_TYPE = "errorType"
        private const val FIELD_ERROR_MESSAGE = "errorMessage"
        private const val FIELD_METADATA = "metadata"
    }

    private val collection by lazy {
        database.getCollection<Document>(config.collectionName)
    }

    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        val historyId = UUID.randomUUID().toString()
        val doc = Document()
            .append(FIELD_HISTORY_ID, historyId)
            .append(FIELD_LOCK_NAME, record.lockName)
            .append(FIELD_TOKEN, record.token)
            .append(FIELD_STATUS, LeaderHistoryStatus.ACQUIRED.name)
            .append(FIELD_STARTED_AT, record.acquiredAt)
            .append(FIELD_LOCKED_UNTIL, record.lockedUntil)
            .append(FIELD_KIND, record.kind.name)
            .append(FIELD_PARTICIPANT_ID, record.nodeId)
            .append(FIELD_SLOT_ID, record.slotId)
            .append(FIELD_METADATA, record.metadata.ifEmpty { null })

        collection.insertOne(doc)
        return LeaderHistoryKey(
            historyId = historyId,
            lockName = record.lockName,
            token = record.token,
            slotId = record.slotId,
        )
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
     * `deleteOlderThan` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int {
        val result = collection.deleteMany(Filters.lt(FIELD_STARTED_AT, cutoff))
        return result.deletedCount.toInt()
    }

    private suspend fun updateFinished(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        status: LeaderHistoryStatus,
        errorType: String?,
        errorMessage: String?,
    ) {
        val updates = Updates.combine(
            Updates.set(FIELD_STATUS, status.name),
            Updates.set(FIELD_FINISHED_AT, finishedAt),
            Updates.set(FIELD_DURATION_MS, durationMs),
            if (errorType != null) Updates.set(FIELD_ERROR_TYPE, errorType) else Updates.unset(FIELD_ERROR_TYPE),
            if (errorMessage != null) Updates.set(FIELD_ERROR_MESSAGE, errorMessage)
            else Updates.unset(FIELD_ERROR_MESSAGE),
        )

        val keyHistoryId = key.historyId
        val result = when {
            keyHistoryId != null ->
                collection.updateOne(Filters.eq(FIELD_HISTORY_ID, keyHistoryId), updates)

            else ->
                collection.updateOne(
                    Filters.and(
                        Filters.eq(FIELD_LOCK_NAME, key.lockName),
                        Filters.eq(FIELD_TOKEN, key.token),
                    ),
                    updates,
                )
        }

        if (result.matchedCount == 0L) {
            log.warn { "No history document updated for key=$key status=$status — possible missing record" }
        }
    }
}

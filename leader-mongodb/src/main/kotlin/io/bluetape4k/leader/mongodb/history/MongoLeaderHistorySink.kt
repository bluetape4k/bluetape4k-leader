package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant

/**
 * `MongoLeaderHistorySink`는 MongoDB history collection에 획득·완료·실패 기록을 저장하고, history key로 종료 상태를 갱신하거나 cutoff 이전 문서를 삭제합니다.
 *
 * @property database 설정된 history collection을 조회하고 문서를 기록·갱신·삭제하는 coroutine MongoDatabase입니다.
 * @property config 사용할 collection 이름과 TTL 보존 정책입니다.
 */
class MongoLeaderHistorySink(
    private val database: MongoDatabase,
    private val config: MongoHistoryConfig = MongoHistoryConfig(),
): SuspendLeaderHistorySink {

    companion object: KLoggingChannel() {
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
        // `nextIdAsString()`은 Base62 인코딩이므로 기존 canonical UUID 문자열
        // 형식과 호환되는 값 객체의 문자열 표현을 사용합니다.
        val historyId = Uuid.V7.nextUUID().toString()
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
     * `startedAt < cutoff`인 history 문서를 삭제하고 삭제 건수를 반환합니다.
     * TTL index가 활성화되면 자동 보존 삭제의 주 경로이며, 이 수동 정리는 `deleteMany`를 사용해 `limit`을 적용하지 않습니다.
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

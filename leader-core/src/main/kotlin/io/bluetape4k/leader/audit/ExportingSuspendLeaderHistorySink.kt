@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.leader.audit

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.audit.internal.LeaderAuditPendingContextStore
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/**
 * suspend history sink의 delegate 결과와 cancellation을 보존하면서 audit event를 전달합니다.
 * terminal delegate 실패·취소에서도 pending context를 `finally`에서 제거합니다.
 */
class ExportingSuspendLeaderHistorySink private constructor(
    private val delegate: SuspendLeaderHistorySink,
    private val exporter: LeaderAuditExporter,
    private val sanitizer: LeaderAuditValueSanitizer,
    private val contexts: LeaderAuditPendingContextStore,
) : SuspendLeaderHistorySink {

    /** 기본 redaction 정책을 사용하는 wrapper입니다. */
    constructor(delegate: SuspendLeaderHistorySink, exporter: LeaderAuditExporter) : this(
        delegate,
        exporter,
        LeaderAuditValueSanitizer.Default,
        LeaderAuditPendingContextStore(),
    )

    /** 지정한 redaction 정책을 사용하는 wrapper입니다. */
    constructor(
        delegate: SuspendLeaderHistorySink,
        exporter: LeaderAuditExporter,
        sanitizer: LeaderAuditValueSanitizer,
    ) : this(delegate, exporter, sanitizer, LeaderAuditPendingContextStore())

    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        currentCoroutineContext().ensureActive()
        val key = delegate.recordAcquired(record)
        currentCoroutineContext().ensureActive()
        if (key != null) {
            contexts.put(key, record)
            submitSafely(LeaderAuditExportEvent.History.from(record, sanitizer))
        }
        return key
    }

    override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        var contextRemoved = false
        try {
            currentCoroutineContext().ensureActive()
            delegate.recordCompleted(key, finishedAt, durationMs)
            currentCoroutineContext().ensureActive()
            val context = contexts.remove(key)
            contextRemoved = true
            submitSafely(
                historyEvent(
                    key,
                    context,
                    finishedAt,
                    durationMs,
                    LeaderHistoryStatus.COMPLETED,
                ),
            )
        } finally {
            if (!contextRemoved) contexts.remove(key)
        }
    }

    override suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) {
        var contextRemoved = false
        try {
            currentCoroutineContext().ensureActive()
            delegate.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
            currentCoroutineContext().ensureActive()
            val context = contexts.remove(key)
            contextRemoved = true
            submitSafely(
                historyEvent(
                    key,
                    context,
                    finishedAt,
                    durationMs,
                    LeaderHistoryStatus.FAILED,
                    errorType,
                    errorMessage,
                ),
            )
        } finally {
            if (!contextRemoved) contexts.remove(key)
        }
    }

    override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int {
        currentCoroutineContext().ensureActive()
        val deleted = delegate.deleteOlderThan(cutoff, limit)
        currentCoroutineContext().ensureActive()
        return deleted
    }

    private fun historyEvent(
        key: LeaderHistoryKey,
        context: LeaderAuditPendingContextStore.PendingContext?,
        finishedAt: Instant,
        durationMs: Long,
        status: LeaderHistoryStatus,
        errorType: String? = null,
        errorMessage: String? = null,
    ): LeaderAuditExportEvent.History {
        val source = context ?: LeaderAuditPendingContextStore.PendingContext(
            lockName = key.lockName,
            kind = io.bluetape4k.leader.LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = finishedAt,
            lockedUntil = finishedAt,
            nodeId = null,
            slotId = key.slotId,
            metadata = mapOf(CONTEXT_ATTRIBUTE to CONTEXT_MISSING),
        )
        return LeaderAuditExportEvent.History.from(
            LeaderLockHistoryRecord(
                lockName = source.lockName,
                token = TOKEN_PLACEHOLDER,
                kind = source.kind,
                acquiredAt = source.acquiredAt,
                lockedUntil = source.lockedUntil,
                nodeId = source.nodeId,
                finishedAt = finishedAt,
                durationMs = durationMs,
                status = status,
                errorType = errorType,
                errorMessage = errorMessage,
                slotId = source.slotId,
                metadata = source.metadata,
            ),
            sanitizer,
        )
    }

    private fun submitSafely(event: LeaderAuditExportEvent) {
        try {
            exporter.submit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit export submission failed and was ignored" }
        }
    }

    private companion object : KLogging() {
        const val TOKEN_PLACEHOLDER: String = "audit-context"
        const val CONTEXT_ATTRIBUTE: String = "audit_context"
        const val CONTEXT_MISSING: String = "missing"
    }
}

@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.leader.audit

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.audit.internal.LeaderAuditPendingContextStore
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * 기존 blocking history sink 결과를 유지하면서 sanitized audit event를 exporter에 전달합니다.
 *
 * delegate 호출이 먼저 실행되고 exporter의 `ACCEPTED` 또는 `DROPPED_*` 결과는 history
 * 저장 결과로 변환되지 않습니다. exporter는 best-effort 부가 경로이며, caller가 executor와
 * scheduler를 소유하는 경우 이 wrapper를 닫기 전에 exporter를 먼저 닫아야 합니다. terminal
 * delegate 실패에서도 pending context는 `finally`에서 제거됩니다.
 */
class ExportingLeaderHistorySink private constructor(
    private val delegate: LeaderHistorySink,
    private val exporter: LeaderAuditExporter,
    private val sanitizer: LeaderAuditValueSanitizer,
    private val contexts: LeaderAuditPendingContextStore,
) : LeaderHistorySink {

    /** 기본 redaction 정책을 사용하는 wrapper입니다. */
    constructor(delegate: LeaderHistorySink, exporter: LeaderAuditExporter) : this(
        delegate,
        exporter,
        LeaderAuditValueSanitizer.Default,
        LeaderAuditPendingContextStore(),
    )

    /** 지정한 redaction 정책을 사용하는 wrapper입니다. */
    constructor(
        delegate: LeaderHistorySink,
        exporter: LeaderAuditExporter,
        sanitizer: LeaderAuditValueSanitizer,
    ) : this(delegate, exporter, sanitizer, LeaderAuditPendingContextStore())

    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        val key = delegate.recordAcquired(record)
        if (key != null) {
            contexts.put(key, record)
            submitSafely(LeaderAuditExportEvent.History.from(record, sanitizer))
        }
        return key
    }

    override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        var contextRemoved = false
        try {
            delegate.recordCompleted(key, finishedAt, durationMs)
            val context = contexts.remove(key)
            contextRemoved = true
            submitSafely(
                historyEvent(
                    key = key,
                    context = context,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                    status = LeaderHistoryStatus.COMPLETED,
                ),
            )
        } finally {
            if (!contextRemoved) contexts.remove(key)
        }
    }

    override fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) {
        var contextRemoved = false
        try {
            delegate.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
            val context = contexts.remove(key)
            contextRemoved = true
            submitSafely(
                historyEvent(
                    key = key,
                    context = context,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                    status = LeaderHistoryStatus.FAILED,
                    errorType = errorType,
                    errorMessage = errorMessage,
                ),
            )
        } finally {
            if (!contextRemoved) contexts.remove(key)
        }
    }

    override fun deleteOlderThan(cutoff: Instant, limit: Int): Int = delegate.deleteOlderThan(cutoff, limit)

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

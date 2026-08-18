package io.bluetape4k.leader.audit

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.effectiveStatus
import io.bluetape4k.support.truncateUtf8
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 외부 exporter가 받을 수 있는 token-free, bounded audit event입니다.
 *
 * `LeaderLockHistoryRecord`의 token과 `LeaderLease` 객체는 이 경계를 통과하지
 * 않습니다. 모든 문자열과 attribute는 factory에서 sanitizer와 byte 상한을
 * 적용한 뒤 immutable snapshot으로 보관합니다.
 */
sealed interface LeaderAuditExportEvent {

    /** event가 발생한 wall-clock 시각입니다. */
    val occurredAt: Instant

    /** sanitizer가 적용된 lock 이름입니다. */
    val lockName: String

    /** sanitizer와 aggregate 상한이 적용된 immutable attributes입니다. */
    val attributes: Map<String, String>

    /** history event와 lifecycle event의 공통 bounded 상수입니다. */
    companion object {
        /** error message의 최대 UTF-8 byte 수입니다. */
        const val MAX_ERROR_MESSAGE_BYTES: Int = 4096

        /** error type의 최대 UTF-8 byte 수입니다. */
        const val MAX_ERROR_TYPE_BYTES: Int = 128

        /** lock/node/slot/leader text의 최대 UTF-8 byte 수입니다. */
        const val MAX_TEXT_FIELD_BYTES: Int = 256

        /** attributes의 최대 항목 수입니다. */
        const val MAX_ATTRIBUTES: Int = 32

        /** attribute key의 최대 UTF-8 byte 수입니다. */
        const val MAX_ATTRIBUTE_KEY_BYTES: Int = 128

        /** attribute value의 최대 UTF-8 byte 수입니다. */
        const val MAX_ATTRIBUTE_VALUE_BYTES: Int = 512

        /** attributes key/value 합계의 최대 UTF-8 byte 수입니다. */
        const val MAX_ATTRIBUTES_TOTAL_BYTES: Int = 8192
    }

    /**
     * history recorder에서 생성한 bounded event입니다.
     *
     * 생성자는 외부에서 직접 호출할 수 없으며 `from` factory가 token을 버리고
     * sanitizer를 적용하는 유일한 진입점입니다.
     */
    class History private constructor(snapshot: Snapshot) : LeaderAuditExportEvent {

        /** factory가 검증·정제한 값만 보관하는 opaque construction payload입니다. */
        private class Snapshot(
            val occurredAt: Instant,
            val lockName: String,
            val kind: LockIdentity.AnnotationKind,
            val status: LeaderHistoryStatus,
            val nodeId: String?,
            val slotId: String?,
            val durationMs: Long?,
            val errorType: String?,
            val errorMessage: String?,
            val attributes: Map<String, String>,
        )

        override val occurredAt: Instant = snapshot.occurredAt
        override val lockName: String = snapshot.lockName
        /** single/group election 분류입니다. */
        val kind: LockIdentity.AnnotationKind = snapshot.kind
        /** history lifecycle 상태입니다. */
        val status: LeaderHistoryStatus = snapshot.status
        /** sanitizer가 적용된 node identity입니다. */
        val nodeId: String? = snapshot.nodeId
        /** sanitizer가 적용된 group slot identity입니다. */
        val slotId: String? = snapshot.slotId
        /** 사용자 작업 실행 시간입니다. */
        val durationMs: Long? = snapshot.durationMs
        /** sanitizer가 적용된 예외 type입니다. */
        val errorType: String? = snapshot.errorType
        /** sanitizer가 적용된 예외 message입니다. */
        val errorMessage: String? = snapshot.errorMessage
        override val attributes: Map<String, String> = snapshot.attributes

        companion object {
            /**
             * history record를 token-free export event로 변환합니다.
             *
             * @param record 내부 history record입니다.
             * @param sanitizer 문자열 redaction 정책입니다.
             * @return immutable bounded history event입니다.
             */
            fun from(
                record: LeaderLockHistoryRecord,
                sanitizer: LeaderAuditValueSanitizer,
            ): History {
                val exportNow = Instant.now()
                val occurredAt = record.finishedAt ?: record.acquiredAt
                return History(
                    Snapshot(
                        occurredAt = occurredAt,
                        lockName = bounded(
                            sanitizer,
                            LeaderAuditField.LOCK_NAME,
                            record.lockName,
                            MAX_TEXT_FIELD_BYTES,
                        ),
                        kind = record.kind,
                        status = record.effectiveStatus(exportNow),
                        nodeId = record.nodeId?.let {
                            bounded(sanitizer, LeaderAuditField.NODE_ID, it, MAX_TEXT_FIELD_BYTES)
                        },
                        slotId = record.slotId?.let {
                            bounded(sanitizer, LeaderAuditField.SLOT_ID, it, MAX_TEXT_FIELD_BYTES)
                        },
                        durationMs = record.durationMs,
                        errorType = record.errorType?.let {
                            bounded(sanitizer, LeaderAuditField.ERROR_TYPE, it, MAX_ERROR_TYPE_BYTES)
                        },
                        errorMessage = record.errorMessage?.let {
                            bounded(sanitizer, LeaderAuditField.ERROR_MESSAGE, it, MAX_ERROR_MESSAGE_BYTES)
                        },
                        attributes = sanitizeAttributes(record.metadata, sanitizer),
                    ),
                )
            }
        }

        override fun toString(): String =
            "History(occurredAt=$occurredAt, lockName=$lockName, kind=$kind, status=$status, " +
                "nodeId=$nodeId, slotId=$slotId, durationMs=$durationMs, errorType=$errorType, " +
                "errorMessage=$errorMessage, attributes=$attributes)"
    }

    /**
     * `LeaderElectionEvent`에서 생성한 bounded lifecycle event입니다.
     */
    class Lifecycle private constructor(snapshot: Snapshot) : LeaderAuditExportEvent {

        /** factory가 검증·정제한 값만 보관하는 opaque construction payload입니다. */
        private class Snapshot(
            val occurredAt: Instant,
            val lockName: String,
            val outcome: LeaderAuditLifecycleOutcome,
            val leaderId: String?,
            val leaseExpiry: Instant?,
            val attributes: Map<String, String>,
        )

        override val occurredAt: Instant = snapshot.occurredAt
        override val lockName: String = snapshot.lockName
        /** lifecycle 결과입니다. */
        val outcome: LeaderAuditLifecycleOutcome = snapshot.outcome
        /** sanitizer가 적용된 leader identity입니다. */
        val leaderId: String? = snapshot.leaderId
        /** leader lease 만료 시각입니다. */
        val leaseExpiry: Instant? = snapshot.leaseExpiry
        override val attributes: Map<String, String> = snapshot.attributes

        companion object {
            /**
             * election event를 token-free export event로 변환합니다.
             *
             * @param event 내부 lifecycle event입니다.
             * @param attributes 호출자가 제공한 context입니다.
             * @param sanitizer 문자열 redaction 정책입니다.
             * @return immutable bounded lifecycle event입니다.
             */
            fun from(
                event: LeaderElectionEvent,
                attributes: Map<String, String>,
                sanitizer: LeaderAuditValueSanitizer,
            ): Lifecycle {
                val elected = event as? LeaderElectionEvent.Elected
                return Lifecycle(
                    Snapshot(
                        occurredAt = Instant.now(),
                        lockName = bounded(
                            sanitizer,
                            LeaderAuditField.LOCK_NAME,
                            event.lockName,
                            MAX_TEXT_FIELD_BYTES,
                        ),
                        outcome = when (event) {
                            is LeaderElectionEvent.Elected -> LeaderAuditLifecycleOutcome.ELECTED
                            is LeaderElectionEvent.Revoked -> LeaderAuditLifecycleOutcome.REVOKED
                            is LeaderElectionEvent.Skipped -> LeaderAuditLifecycleOutcome.SKIPPED
                        },
                        leaderId = elected?.leaderId?.let {
                            bounded(sanitizer, LeaderAuditField.LEADER_ID, it, MAX_TEXT_FIELD_BYTES)
                        },
                        leaseExpiry = elected?.leaseExpiry,
                        attributes = sanitizeAttributes(attributes, sanitizer),
                    ),
                )
            }
        }

        override fun toString(): String =
            "Lifecycle(occurredAt=$occurredAt, lockName=$lockName, outcome=$outcome, " +
                "leaderId=$leaderId, leaseExpiry=$leaseExpiry, attributes=$attributes)"
    }
}

private fun bounded(
    sanitizer: LeaderAuditValueSanitizer,
    field: LeaderAuditField,
    value: String,
    maxBytes: Int,
): String {
    val sanitized = if (sanitizer is LeaderAuditValueSanitizer.Raw &&
        field !in LeaderAuditValueSanitizer.RAW_ALLOWED_FIELDS
    ) {
        // Raw는 KIND만 직접 허용하므로 event의 민감한 문자열은 안전한 기본 redaction으로 처리합니다.
        LeaderAuditValueSanitizer.Default.sanitize(field, value)
    } else {
        sanitizer.sanitize(field, value)
    }
    return sanitized.truncateUtf8(maxBytes)
}

private fun sanitizeAttributes(
    source: Map<String, String>,
    sanitizer: LeaderAuditValueSanitizer,
): Map<String, String> {
    val candidates = source.entries
        .map { entry ->
            val sanitizedKey = bounded(
                sanitizer,
                LeaderAuditField.ATTRIBUTE_KEY,
                entry.key,
                LeaderAuditExportEvent.MAX_ATTRIBUTE_KEY_BYTES,
            )
            val sanitizedValue = bounded(
                sanitizer,
                LeaderAuditField.ATTRIBUTE_VALUE,
                entry.value,
                LeaderAuditExportEvent.MAX_ATTRIBUTE_VALUE_BYTES,
            )
            Triple(entry.key, sanitizedKey, sanitizedValue)
        }
        .sortedWith { left, right ->
            compareUtf8(left.second, right.second).takeUnless { it == 0 }
                ?: compareUtf8(left.first, right.first)
        }

    val result = LinkedHashMap<String, String>()
    var totalBytes = 0
    candidates.forEach { (_, sanitizedKey, sanitizedValue) ->
        if (result.size >= LeaderAuditExportEvent.MAX_ATTRIBUTES || result.containsKey(sanitizedKey)) return@forEach
        val entryBytes = sanitizedKey.utf8Size() + sanitizedValue.utf8Size()
        if (totalBytes + entryBytes > LeaderAuditExportEvent.MAX_ATTRIBUTES_TOTAL_BYTES) return@forEach
        result[sanitizedKey] = sanitizedValue
        totalBytes += entryBytes
    }
    return Collections.unmodifiableMap(LinkedHashMap(result))
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    val commonLength = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until commonLength) {
        val comparison = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size - rightBytes.size
}

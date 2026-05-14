package io.bluetape4k.leader.history

import io.bluetape4k.leader.internal.truncateUtf8

private val LOG_SANITIZE_REGEX = Regex("[\\p{Cntrl}\\u2028\\u2029]")

/**
 * Replaces control characters and Unicode line/paragraph separators with `?` to
 * prevent log-injection attacks.
 *
 * This is a **log-injection defence**, not a credential scrubber.  JDBC driver
 * exception messages may still contain sensitive data such as connection URLs or
 * passwords.  Callers are responsible for redacting credentials before they reach
 * the recorder layer.
 */
internal fun String.sanitizeForLog(): String =
    replace(LOG_SANITIZE_REGEX, "?")

/**
 * Returns a copy of [record] with [LeaderLockHistoryRecord.errorMessage] truncated and
 * [LeaderLockHistoryRecord.metadata] keys/values sanitized and capped.
 *
 * Sanitization rules:
 * - [LeaderLockHistoryRecord.errorMessage]: `sanitizeForLog()` then `truncateUtf8(MAX_ERROR_MESSAGE_BYTES)`.
 * - [LeaderLockHistoryRecord.metadata]: capped to [LeaderLockHistoryRecord.MAX_METADATA_KEYS] entries
 *   (iteration order is non-deterministic for arbitrary [Map] implementations — prefer
 *   [LinkedHashMap] if insertion order matters); each key is `take(64).sanitizeForLog()`,
 *   each value is `take(MAX_METADATA_VALUE_LENGTH).sanitizeForLog()`.
 */
internal fun sanitize(record: LeaderLockHistoryRecord): LeaderLockHistoryRecord {
    val sanitizedMessage = record.errorMessage
        ?.sanitizeForLog()
        ?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES)

    val sanitizedMetadata = if (record.metadata.isEmpty()) {
        record.metadata
    } else {
        record.metadata.entries
            .take(LeaderLockHistoryRecord.MAX_METADATA_KEYS)
            .associate { (k, v) ->
                k.take(64).sanitizeForLog() to v.take(LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH).sanitizeForLog()
            }
    }

    return if (sanitizedMessage == record.errorMessage && sanitizedMetadata == record.metadata) {
        record
    } else {
        record.withSanitizedContent(errorMessage = sanitizedMessage, metadata = sanitizedMetadata)
    }
}

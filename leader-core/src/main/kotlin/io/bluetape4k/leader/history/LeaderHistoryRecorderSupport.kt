package io.bluetape4k.leader.history

import io.bluetape4k.leader.internal.truncateUtf8

private const val MAX_METADATA_KEY_LENGTH = 64

/**
 * Replaces control characters and Unicode line/paragraph separators with `?` to
 * prevent log-injection attacks.
 *
 * This is a **log-injection defence**, not a credential scrubber.  JDBC driver
 * exception messages may still contain sensitive data such as connection URLs or
 * passwords.  Callers are responsible for redacting credentials before they reach
 * the recorder layer.
 */
internal fun String.sanitizeForLog(): String {
    val firstUnsafe = indexOfFirstUnsafeLogChar()
    if (firstUnsafe < 0) {
        return this
    }

    val chars = toCharArray()
    for (index in firstUnsafe..chars.lastIndex) {
        if (chars[index].isUnsafeLogChar()) {
            chars[index] = '?'
        }
    }
    return String(chars)
}

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

    val sanitizedMetadata = sanitizeMetadata(record.metadata)

    return if (sanitizedMessage == record.errorMessage && sanitizedMetadata == record.metadata) {
        record
    } else {
        record.withSanitizedContent(errorMessage = sanitizedMessage, metadata = sanitizedMetadata)
    }
}

private fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> {
    if (metadata.isEmpty() || metadata.isAlreadySafe()) {
        return metadata
    }

    return metadata.entries
        .take(LeaderLockHistoryRecord.MAX_METADATA_KEYS)
        .associate { (key, value) ->
            key.take(MAX_METADATA_KEY_LENGTH).sanitizeForLog() to
                value.take(LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH).sanitizeForLog()
        }
}

private fun Map<String, String>.isAlreadySafe(): Boolean {
    if (size > LeaderLockHistoryRecord.MAX_METADATA_KEYS) {
        return false
    }

    return entries.all { (key, value) ->
        key.length <= MAX_METADATA_KEY_LENGTH &&
            value.length <= LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH &&
            !key.hasUnsafeLogChar() &&
            !value.hasUnsafeLogChar()
    }
}

private fun String.hasUnsafeLogChar(): Boolean =
    indexOfFirstUnsafeLogChar() >= 0

private fun String.indexOfFirstUnsafeLogChar(): Int {
    for (index in indices) {
        if (this[index].isUnsafeLogChar()) {
            return index
        }
    }
    return -1
}

private fun Char.isUnsafeLogChar(): Boolean =
    code < 0x20 || code in 0x7F..0x9F || code == 0x2028 || code == 0x2029

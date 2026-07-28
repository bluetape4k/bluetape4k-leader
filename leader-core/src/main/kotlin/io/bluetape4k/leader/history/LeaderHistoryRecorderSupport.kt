package io.bluetape4k.leader.history

import io.bluetape4k.support.truncateUtf8

private const val MAX_METADATA_KEY_LENGTH = 64

/**
 * `String` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
 * `sanitize` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param record `record` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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

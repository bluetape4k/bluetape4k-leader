package io.bluetape4k.leader.history

import io.bluetape4k.support.truncateUtf8

private const val MAX_METADATA_KEY_LENGTH = 64

/**
 * log line separator와 제어 문자를 `?`로 바꿉니다.
 * @return 안전 문자로 치환한 문자열. 이미 안전하면 receiver를 그대로 반환합니다.
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
 * 오류 메시지와 metadata를 log-safe 문자로 바꾸고, 바이트·문자 수 및 key 수 제한을 적용합니다.
 * @param record 정리할 lock history record
 * @return 내용 변경이 필요 없으면 같은 record, 변경이 있으면 정리된 record 복사본
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

package io.bluetape4k.leader.audit

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.support.truncateUtf8
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * audit event의 문자열 field를 안전한 외부 표현으로 변환하는 정책입니다.
 *
 * 임의 lambda를 공개하지 않고 제한된 정책만 제공하여 token, credential, raw
 * high-cardinality 값을 실수로 export하는 경계를 고정합니다.
 * 모든 정책은 `KIND` 입력을 `SINGLE` 또는 `GROUP` canonical name으로 먼저 검증하며,
 * 그 밖의 값은 `IllegalArgumentException`으로 거부합니다.
 */
sealed interface LeaderAuditValueSanitizer {

    /**
     * field의 값을 정책에 따라 변환합니다.
     *
     * @param field 변환 대상 field입니다.
     * @param value 원본 문자열입니다. `KIND`는 `SINGLE` 또는 `GROUP`만 허용합니다.
     * @return 정책이 허용하는 외부 표현입니다.
     */
    fun sanitize(field: LeaderAuditField, value: String): String

    /**
     * 기본 redaction 정책입니다. `KIND`만 low-cardinality 값으로 유지하고 나머지는
     * 고정된 문자열로 치환합니다.
     */
    data object Default : LeaderAuditValueSanitizer {
        override fun sanitize(field: LeaderAuditField, value: String): String {
            requireValidKind(field, value)
            return if (field == LeaderAuditField.KIND) value else AUDIT_REDACTED
        }
    }

    /**
     * SHA-256 hex digest로 값을 변환하는 명시적 opt-in 정책입니다.
     */
    data object Hash : LeaderAuditValueSanitizer {
        override fun sanitize(field: LeaderAuditField, value: String): String {
            requireValidKind(field, value)
            return sha256Hex(value)
        }
    }

    /**
     * 원본의 UTF-8 byte 길이를 제한하는 명시적 opt-in 정책입니다.
     *
     * code point 경계에서만 자르므로 잘못된 UTF-8 surrogate를 만들지 않습니다.
     */
    data class Truncate(val maxBytes: Int) : LeaderAuditValueSanitizer {
        init {
            require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
        }

        override fun sanitize(field: LeaderAuditField, value: String): String {
            requireValidKind(field, value)
            return value.truncateUtf8(maxBytes)
        }
    }

    /**
     * 명시적으로 허용한 field에만 원본을 유지하는 정책입니다.
     *
     * 현재 v1에서는 low-cardinality `KIND`만 raw export를 허용합니다. 허용 목록과
     * byte 상한은 생성 시 defensive copy되며, 다른 field를 요청하면 실패합니다.
     */
    class Raw(
        allowList: Set<LeaderAuditField>,
        val maxBytes: Int,
    ) : LeaderAuditValueSanitizer {
        private val copiedAllowList: Set<LeaderAuditField>

        init {
            require(allowList.isNotEmpty()) { "allowList must not be empty" }
            require(allowList.all { it in RAW_ALLOWED_FIELDS }) {
                "Raw export is only allowed for $RAW_ALLOWED_FIELDS: $allowList"
            }
            require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
            copiedAllowList = Collections.unmodifiableSet(allowList.toSet())
        }

        override fun sanitize(field: LeaderAuditField, value: String): String {
            requireValidKind(field, value)
            require(field in copiedAllowList) {
                "Raw export is not allowed for field=$field; allowList=$copiedAllowList"
            }
            return value.truncateUtf8(maxBytes)
        }

    }

    companion object {
        /** v1에서 raw export를 허용하는 field의 고정 목록입니다. */
        val RAW_ALLOWED_FIELDS: Set<LeaderAuditField> =
            Collections.unmodifiableSet(setOf(LeaderAuditField.KIND))
    }
}

/** audit event 문자열 field의 종류입니다. */
enum class LeaderAuditField {
    LOCK_NAME,
    KIND,
    NODE_ID,
    SLOT_ID,
    LEADER_ID,
    ERROR_TYPE,
    ERROR_MESSAGE,
    ATTRIBUTE_KEY,
    ATTRIBUTE_VALUE,
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr NIBBLE_SHIFT) and NIBBLE_MASK])
            append(HEX_DIGITS[byte.toInt() and NIBBLE_MASK])
        }
    }
}

private fun requireValidKind(field: LeaderAuditField, value: String) {
    if (field == LeaderAuditField.KIND) {
        require(
            value == LockIdentity.AnnotationKind.SINGLE.name ||
                value == LockIdentity.AnnotationKind.GROUP.name,
        ) {
            "KIND must be SINGLE or GROUP"
        }
    }
}

private const val NIBBLE_SHIFT = 4
private const val NIBBLE_MASK = 0x0f
private const val HEX_DIGITS = "0123456789abcdef"
private const val AUDIT_REDACTED = "redacted"

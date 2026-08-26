package io.bluetape4k.leader.audit.http

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.leader.audit.LeaderAuditExportEvent

/**
 * 정제된 audit event를 HTTP 요청 payload로 변환하는 함수형 계약입니다.
 *
 * encoder는 JSON 같은 wire format을 선택하지만 core에는 serialization dependency를
 * 추가하지 않습니다. 반환 payload는 생성 시점에 caller 입력 배열을 복사합니다.
 */
fun interface LeaderAuditPayloadEncoder {

    /** 하나의 sanitized event를 immutable HTTP payload로 인코딩합니다. */
    fun encode(event: LeaderAuditExportEvent): LeaderAuditHttpPayload
}

/**
 * HTTP 요청 body와 content type을 함께 보관하는 bounded immutable payload입니다.
 *
 * body 크기는 항상 1 MiB 이하이며, `body()`는 매번 방어적 복사본을 반환합니다.
 */
class LeaderAuditHttpPayload private constructor(
    /** HTTP `Content-Type` 값입니다. */
    val contentType: String,
    private val bytes: ByteArray,
) {

    /** 요청 전송에 사용할 body의 방어적 복사본을 반환합니다. */
    fun body(): ByteArray = bytes.copyOf()

    companion object {
        /** 검증·복사된 payload를 생성합니다. */
        @JvmStatic
        fun of(contentType: String, body: ByteArray): LeaderAuditHttpPayload {
            val validContentType = contentType.requireNotBlank("contentType")
            require(!validContentType.containsHttpControlCharacter()) {
                "contentType must not contain control characters"
            }
            body.size.requireLe(HARD_MAX_PAYLOAD_BYTES, "body.bytes")
            return LeaderAuditHttpPayload(validContentType, body.copyOf())
        }
    }
}

private const val HARD_MAX_PAYLOAD_BYTES: Int = 1024 * 1024
private const val HTTP_CONTROL_CHARACTER_MIN_CODE: Int = 0x20
private const val HTTP_DELETE_CHARACTER_CODE: Int = 0x7f

private fun String.containsHttpControlCharacter(): Boolean = any {
    it.code < HTTP_CONTROL_CHARACTER_MIN_CODE || it.code == HTTP_DELETE_CHARACTER_CODE
}

package io.bluetape4k.leader.audit.http

import io.bluetape4k.support.requireInRange

/**
 * HTTP audit delivery의 payload bound를 정의합니다.
 *
 * 응답 body는 항상 `BodyHandlers.discarding()`으로 폐기되며, 이 옵션은 request body의
 * retention bound만 제어합니다. 네트워크 ingress truncation을 보장하지 않습니다.
 */
class LeaderAuditHttpOptions(
    /** encoder payload에 적용할 최대 byte 수입니다. */
    val maxPayloadBytes: Int,
) {

    init {
        maxPayloadBytes.requireInRange(1, HARD_MAX_PAYLOAD_BYTES, "maxPayloadBytes")
    }

    companion object {
        /** 기본 payload bound(64 KiB)를 반환합니다. */
        @JvmStatic
        fun defaults(): LeaderAuditHttpOptions = LeaderAuditHttpOptions(DEFAULT_MAX_PAYLOAD_BYTES)
    }
}

private const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 64 * 1024
private const val HARD_MAX_PAYLOAD_BYTES: Int = 1024 * 1024

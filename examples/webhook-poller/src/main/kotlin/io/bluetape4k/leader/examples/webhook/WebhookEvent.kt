package io.bluetape4k.leader.examples.webhook

import java.time.Instant

/**
 * Webhook event 의 처리 상태.
 */
enum class WebhookEventStatus {
    PENDING,    // 대기 중 — 처리 안 됨
    CLAIMED,    // 폴러 인스턴스가 점유 (atomic findOneAndUpdate)
    DONE,       // 정상 처리 완료
    FAILED,     // maxAttempts 도달 — DLQ 대체
}

/**
 * Webhook event 도메인 모델.
 *
 * Mongo collection 의 한 document 를 표현.
 *
 * ## 필드
 *
 * - [eventId]: 외부 webhook 의 고유 ID (idempotency key)
 * - [payload]: 외부에서 받은 raw payload (JSON 문자열 등)
 * - [status]: 처리 상태 [WebhookEventStatus]
 * - [claimedBy]: 점유한 polling 인스턴스의 nodeId (CLAIMED 상태에서만 의미 있음)
 * - [claimExpiresAt]: 점유 만료 시각. 만료 후 다른 인스턴스 reclaim 가능 (lease 만료)
 * - [attempts]: handler 실행 횟수 — maxAttempts 도달 시 FAILED
 * - [lastError]: 직전 handler 예외 메시지
 * - [createdAt]: event 발생 시각
 */
data class WebhookEvent(
    val eventId: String,
    val payload: String,
    val status: WebhookEventStatus = WebhookEventStatus.PENDING,
    val claimedBy: String? = null,
    val claimExpiresAt: Instant? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Instant = Instant.now(),
)

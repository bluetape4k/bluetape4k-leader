package io.bluetape4k.leader.examples.webhook

import java.time.Instant

/**
 * `WebhookEventStatus`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property eventId example workflow 계약에서 사용하는 속성입니다.
 * @property payload example workflow 계약에서 사용하는 속성입니다.
 * @property status example workflow 계약에서 사용하는 속성입니다.
 * @property claimedBy example workflow 계약에서 사용하는 속성입니다.
 * @property claimExpiresAt example workflow 계약에서 사용하는 속성입니다.
 * @property attempts example workflow 계약에서 사용하는 속성입니다.
 * @property lastError example workflow 계약에서 사용하는 속성입니다.
 * @property createdAt example workflow 계약에서 사용하는 속성입니다.
 */
enum class WebhookEventStatus {
    PENDING,    // Waiting to be processed.
    CLAIMED,    // Claimed by a poller instance through atomic findOneAndUpdate.
    DONE,       // Processed successfully.
    FAILED,     // maxAttempts reached; acts as the DLQ terminal state.
}

/**
 * `WebhookEvent`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property eventId example workflow 계약에서 `eventId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property payload example workflow 계약에서 `payload` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property claimedBy example workflow 계약에서 `claimedBy` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property claimExpiresAt example workflow 계약에서 `claimExpiresAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property attempts example workflow 계약에서 `attempts` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lastError example workflow 계약에서 `lastError` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property createdAt example workflow 계약에서 `createdAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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

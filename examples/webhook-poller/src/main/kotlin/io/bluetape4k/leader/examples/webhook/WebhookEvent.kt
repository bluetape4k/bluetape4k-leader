package io.bluetape4k.leader.examples.webhook

import java.time.Instant

/**
 * Processing state for a webhook event.
 */
enum class WebhookEventStatus {
    PENDING,    // Waiting to be processed.
    CLAIMED,    // Claimed by a poller instance through atomic findOneAndUpdate.
    DONE,       // Processed successfully.
    FAILED,     // maxAttempts reached; acts as the DLQ terminal state.
}

/**
 * Domain model for a webhook event.
 *
 * Represents one document in the Mongo collection.
 *
 * ## Fields
 *
 * - [eventId]: unique external webhook ID used as the idempotency key.
 * - [payload]: raw external payload, such as a JSON string.
 * - [status]: processing state represented by [WebhookEventStatus].
 * - [claimedBy]: node ID of the poller instance that owns the claim; meaningful only while
 *   [status] is [WebhookEventStatus.CLAIMED].
 * - [claimExpiresAt]: claim expiration time. Another instance may reclaim the event after this lease expires.
 * - [attempts]: number of handler attempts. The event becomes [WebhookEventStatus.FAILED] when
 *   this reaches `maxAttempts`.
 * - [lastError]: previous handler exception message.
 * - [createdAt]: event creation time.
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

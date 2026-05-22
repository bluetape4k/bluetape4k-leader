package io.bluetape4k.leader.examples.webhook

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [WebhookPoller].
 *
 * ## Behavior / Contract
 *
 * - [nodeId]: identifier for this poller instance. Stored in the claimed event's `claimedBy` field.
 * - [lockName]: leader-election lock name. Prefer a separate lock per event collection.
 * - [pollInterval]: pause between polling cycles. Leaders use it after each batch, and non-leaders
 *   use it before retrying after a failed leader-lock acquisition.
 * - [batchSize]: maximum number of events to process per cycle; also the upper bound for atomic claim retries.
 * - [maxAttempts]: maximum accumulated handler attempts. The event status becomes `FAILED` when reached.
 * - [claimDuration]: lease duration after a claim before another instance may reclaim the event.
 *   If the handler can run longer than [claimDuration], another instance may reclaim the same event, so set
 *   this to at least the average handler runtime plus a safety margin, such as 2x.
 *
 * ### Leader-Election Options Are Owned By The Elector
 *
 * Configure leader-lock options such as `waitTime` and `leaseTime` on the externally supplied elector,
 * for example through [io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector]'s
 * `MongoLeaderElectionOptions.leaderOptions`. Keeping those settings outside this type avoids
 * configuration drift.
 *
 * ```kotlin
 * WebhookPollerOptions(
 *     nodeId = System.getenv("HOSTNAME"),
 *     lockName = "webhook-poller:prod",
 *     pollInterval = 500.milliseconds,
 *     batchSize = 20,
 *     maxAttempts = 5,
 *     claimDuration = 30.seconds,
 * )
 * ```
 */
data class WebhookPollerOptions(
    val nodeId: String,
    val lockName: String,
    val pollInterval: Duration = 1.seconds,
    val batchSize: Int = 10,
    val maxAttempts: Int = 5,
    val claimDuration: Duration = 30.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
        pollInterval.inWholeMilliseconds.requirePositiveNumber("pollInterval")
        batchSize.requirePositiveNumber("batchSize")
        maxAttempts.requirePositiveNumber("maxAttempts")
        claimDuration.inWholeMilliseconds.requirePositiveNumber("claimDuration")
    }
}

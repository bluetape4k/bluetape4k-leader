package io.bluetape4k.leader.examples.webhook

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.Document
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Distributed webhook event polling worker.
 *
 * ## Behavior / Contract
 *
 * - Uses [SuspendLeaderElector] so only one instance polls in a multi-instance deployment.
 * - The elected leader atomically claims up to [WebhookPollerOptions.batchSize] events and then runs [handler].
 * - Non-leaders sleep briefly and retry, allowing automatic handoff when the current leader dies.
 *
 * ### Claim Model
 *
 * 1. Atomically claims a PENDING or expired CLAIMED event with `findOneAndUpdate`.
 *    - filter: `{ attempts: { $lt: maxAttempts } }` AND
 *      (`{ status: PENDING }` OR `{ status: CLAIMED, claimExpiresAt: { $lt: now } }`)
 *    - update: `status=CLAIMED, claimedBy=nodeId, claimExpiresAt=now+claimDuration, $inc attempts: 1`
 * 2. **Attempts are incremented exactly once at claim time**. Handler success or failure does not increment again.
 * 3. The claim itself is the attempt.
 * 4. Expired CLAIMED events with `attempts >= maxAttempts` are not reclaimed (P2-3);
 *    a separate sweeper should clean them up.
 *
 * ### Failure Transition
 *
 * If [handler] throws, the state changes **only if this instance still owns the claim**:
 * - `attempts >= maxAttempts` -> `status=FAILED`, `lastError=ex.message`, acting as the DLQ terminal state.
 * - Otherwise -> `status=PENDING`, `claimedBy=null`, `claimExpiresAt=null`, `lastError=ex.message`,
 *   allowing the next cycle to reclaim it immediately.
 *
 * If this instance wakes up late after another instance reclaimed the event due to claim expiration (P2-2),
 * the update is ignored and a `claim ownership lost` log is emitted.
 *
 * ### Graceful Stop
 *
 * [stopGracefully] cancels the polling job and joins it within the timeout.
 * Events already claimed at cancellation time remain `CLAIMED`; after `claimExpiresAt` expires,
 * another instance may reclaim and process them, preserving at-least-once delivery.
 *
 * ### Indexes
 *
 * The first [start] call creates a `(status, claimExpiresAt)` compound index for claim-query performance.
 *
 * ```kotlin
 * val elector = MongoSuspendLeaderElector(lockCollection)
 * val poller = WebhookPoller(elector, eventCollection, options) { event ->
 *     httpClient.post(targetUrl, event.payload)   // Deliver the webhook externally.
 * }
 * val job = poller.start(applicationScope)
 * // ... shutdown ...
 * poller.stopGracefully()
 * ```
 *
 * @param elector externally supplied [SuspendLeaderElector], making tests and backend replacement simple.
 * @param eventCollection collection that stores [WebhookEvent] documents; separate from the lock collection.
 * @param options polling behavior settings.
 * @param handler event-processing callback. Exceptions are isolated so the next event can continue.
 */
class WebhookPoller(
    private val elector: SuspendLeaderElector,
    private val eventCollection: MongoCollection<Document>,
    val options: WebhookPollerOptions,
    private val handler: suspend (WebhookEvent) -> Unit,
) {

    companion object: KLoggingChannel() {
        internal const val FIELD_EVENT_ID = "eventId"
        internal const val FIELD_PAYLOAD = "payload"
        internal const val FIELD_STATUS = "status"
        internal const val FIELD_CLAIMED_BY = "claimedBy"
        internal const val FIELD_CLAIM_EXPIRES_AT = "claimExpiresAt"
        internal const val FIELD_ATTEMPTS = "attempts"
        internal const val FIELD_LAST_ERROR = "lastError"
        internal const val FIELD_CREATED_AT = "createdAt"
    }

    /**
     * Serializes concurrent [start] and [stopGracefully] calls.
     *
     * Uses [ReentrantLock] instead of `synchronized` so it remains safe in virtual-thread environments.
     */
    private val lifecycleLock = ReentrantLock()

    @Volatile
    private var pollerJob: Job? = null

    @Volatile
    private var indexEnsured: Boolean = false

    /**
     * Starts the polling loop and returns its [Job].
     *
     * - Calling this twice on the same instance is not allowed; an active poller throws [IllegalStateException].
     * - Cancelling [scope] stops the loop automatically.
     */
    fun start(scope: CoroutineScope): Job = lifecycleLock.withLock {
        check(pollerJob == null || pollerJob?.isActive != true) {
            "WebhookPoller(nodeId=${options.nodeId}) is already running"
        }
        val job = scope.launch {
            try {
                ensureIndexes()
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[${options.nodeId}] webhook poller loop terminated unexpectedly" }
                throw e
            }
        }
        pollerJob = job
        job
    }

    /**
     * Gracefully stops the polling loop. If it does not stop within [timeout], returns after forced cancellation.
     */
    suspend fun stopGracefully(timeout: Duration = 30.seconds) {
        val job = lifecycleLock.withLock { pollerJob } ?: return
        try {
            withTimeoutOrNull(timeout) { job.cancelAndJoin() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] stopGracefully encountered error" }
        } finally {
            lifecycleLock.withLock {
                if (pollerJob === job) pollerJob = null
            }
        }
    }

    private suspend fun ensureIndexes() {
        if (indexEnsured) return
        try {
            eventCollection.createIndex(
                Indexes.ascending(FIELD_STATUS, FIELD_CLAIM_EXPIRES_AT),
                IndexOptions().name("idx_status_claim_expires_at").background(true),
            )
            eventCollection.createIndex(
                Indexes.ascending(FIELD_EVENT_ID),
                IndexOptions().name("idx_event_id").unique(true).background(true),
            )
            indexEnsured = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Index creation is not fatal; the poller can continue with slower collection scans.
            log.warn(e) { "[${options.nodeId}] failed to create webhook event index; falling back to collection scans" }
            indexEnsured = true
        }
    }

    private suspend fun runLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                elector.runIfLeader(options.lockName) {
                    log.debug { "[${options.nodeId}] elected as leader; starting batch processing" }
                    processBatch()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[${options.nodeId}] leader cycle failed; retrying on next cycle" }
            }

            // Leaders and non-leaders use the same cycle pause; lock waitTime is owned by the elector options.
            delay(options.pollInterval)
        }
    }

    /**
     * Processes one batch by claiming and handling up to [WebhookPollerOptions.batchSize] events.
     * @return number of processed events.
     */
    private suspend fun processBatch(): Int {
        var processed = 0
        repeat(options.batchSize) {
            val event = claimNext() ?: return processed
            handleSingle(event)
            processed++
        }
        return processed
    }

    /**
     * Atomically claims a PENDING or expired CLAIMED event with `findOneAndUpdate`.
     * @return the claimed [WebhookEvent], or null when no event is available.
     */
    private suspend fun claimNext(): WebhookEvent? {
        val now = Instant.now()
        val claimExpiresAt = now.plusMillis(options.claimDuration.inWholeMilliseconds)

        // P2-3: do not reclaim expired CLAIMED events when attempts >= maxAttempts.
        // An orphaned CLAIMED event may freeze just before maxAttempts; a separate sweeper should clean it up.
        val filter = Filters.and(
            Filters.lt(FIELD_ATTEMPTS, options.maxAttempts),
            Filters.or(
                Filters.eq(FIELD_STATUS, WebhookEventStatus.PENDING.name),
                Filters.and(
                    Filters.eq(FIELD_STATUS, WebhookEventStatus.CLAIMED.name),
                    Filters.lt(FIELD_CLAIM_EXPIRES_AT, now),
                ),
            ),
        )
        val update = Updates.combine(
            Updates.set(FIELD_STATUS, WebhookEventStatus.CLAIMED.name),
            Updates.set(FIELD_CLAIMED_BY, options.nodeId),
            Updates.set(FIELD_CLAIM_EXPIRES_AT, claimExpiresAt),
            Updates.inc(FIELD_ATTEMPTS, 1),
        )
        val opts = FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.AFTER)
            .sort(Sorts.ascending(FIELD_CREATED_AT))

        val updated = try {
            eventCollection.findOneAndUpdate(filter, update, opts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] claimNext failed; retrying on next cycle" }
            return null
        }

        return updated?.toWebhookEvent()
    }

    /**
     * Handles one event. Handler exceptions are isolated so the next event can continue.
     */
    private suspend fun handleSingle(event: WebhookEvent) {
        try {
            handler(event)
            markDone(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] handler failed eventId=${event.eventId} attempts=${event.attempts}" }
            markFailureOrRequeue(event, e)
        }
    }

    private suspend fun markDone(event: WebhookEvent) {
        try {
            // P2-2: verify claim ownership so a stale owner cannot overwrite the new owner's CLAIMED state.
            val result = eventCollection.updateOne(
                ownedClaimFilter(event),
                Updates.combine(
                    Updates.set(FIELD_STATUS, WebhookEventStatus.DONE.name),
                    Updates.set(FIELD_LAST_ERROR, null),
                ),
            )
            if (result.matchedCount == 0L) {
                log.warn {
                    "[${options.nodeId}] markDone — claim ownership lost eventId=${event.eventId} (skip update)"
                }
            } else {
                log.debug { "[${options.nodeId}] eventId=${event.eventId} DONE (attempts=${event.attempts})" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] markDone failed eventId=${event.eventId}" }
        }
    }

    private suspend fun markFailureOrRequeue(event: WebhookEvent, ex: Exception) {
        // attempts was already incremented at claim time and reflected in the event.
        val errorMessage = ex.message ?: ex::class.qualifiedName ?: "unknown"
        try {
            // P2-2: verify claim ownership so a stale owner cannot overwrite the new owner's CLAIMED state.
            val ownership = ownedClaimFilter(event)
            val result = if (event.attempts >= options.maxAttempts) {
                // DLQ substitute: terminal FAILED state.
                eventCollection.updateOne(
                    ownership,
                    Updates.combine(
                        Updates.set(FIELD_STATUS, WebhookEventStatus.FAILED.name),
                        Updates.set(FIELD_LAST_ERROR, errorMessage),
                    ),
                )
            } else {
                // Retryable: return to PENDING.
                eventCollection.updateOne(
                    ownership,
                    Updates.combine(
                        Updates.set(FIELD_STATUS, WebhookEventStatus.PENDING.name),
                        Updates.set(FIELD_CLAIMED_BY, null),
                        Updates.set(FIELD_CLAIM_EXPIRES_AT, null),
                        Updates.set(FIELD_LAST_ERROR, errorMessage),
                    ),
                )
            }
            if (result.matchedCount == 0L) {
                log.warn {
                    "[${options.nodeId}] markFailureOrRequeue — claim ownership lost " +
                        "eventId=${event.eventId} (skip update)"
                }
            } else if (event.attempts >= options.maxAttempts) {
                log.info {
                    "[${options.nodeId}] eventId=${event.eventId} FAILED " +
                        "(maxAttempts=${options.maxAttempts} reached)"
                }
            } else {
                log.debug {
                    "[${options.nodeId}] eventId=${event.eventId} returned to PENDING " +
                        "(attempts=${event.attempts}/${options.maxAttempts})"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] markFailureOrRequeue failed eventId=${event.eventId}" }
        }
    }

    /**
     * P2-2: filter that verifies claim ownership.
     *
     * Matches only documents where the `(eventId, status=CLAIMED, claimedBy=nodeId, claimExpiresAt)` tuple
     * written by this instance at claim time is still intact. If another instance reclaimed the event,
     * `claimExpiresAt` or `claimedBy` changes and the match fails with `matchedCount=0`.
     */
    private fun ownedClaimFilter(event: WebhookEvent): org.bson.conversions.Bson = Filters.and(
        Filters.eq(FIELD_EVENT_ID, event.eventId),
        Filters.eq(FIELD_STATUS, WebhookEventStatus.CLAIMED.name),
        Filters.eq(FIELD_CLAIMED_BY, options.nodeId),
        event.claimExpiresAt
            ?.let { Filters.eq(FIELD_CLAIM_EXPIRES_AT, java.util.Date.from(it)) }
            ?: Filters.exists(FIELD_CLAIM_EXPIRES_AT, true),
    )
}

/** Converts a Mongo [Document] to [WebhookEvent]. */
internal fun Document.toWebhookEvent(): WebhookEvent {
    val statusStr = getString(WebhookPoller.FIELD_STATUS) ?: WebhookEventStatus.PENDING.name
    val claimExpiresAtDate: java.util.Date? = get(WebhookPoller.FIELD_CLAIM_EXPIRES_AT) as? java.util.Date
    val createdAtDate: java.util.Date? = get(WebhookPoller.FIELD_CREATED_AT) as? java.util.Date
    return WebhookEvent(
        eventId = getString(WebhookPoller.FIELD_EVENT_ID).orEmpty(),
        payload = getString(WebhookPoller.FIELD_PAYLOAD).orEmpty(),
        status = WebhookEventStatus.valueOf(statusStr),
        claimedBy = getString(WebhookPoller.FIELD_CLAIMED_BY),
        claimExpiresAt = claimExpiresAtDate?.toInstant(),
        attempts = getInteger(WebhookPoller.FIELD_ATTEMPTS, 0),
        lastError = getString(WebhookPoller.FIELD_LAST_ERROR),
        createdAt = createdAtDate?.toInstant() ?: Instant.EPOCH,
    )
}

/** Converts [WebhookEvent] to a Mongo [Document] for inserts. */
internal fun WebhookEvent.toDocument(): Document = Document().apply {
    put(WebhookPoller.FIELD_EVENT_ID, eventId)
    put(WebhookPoller.FIELD_PAYLOAD, payload)
    put(WebhookPoller.FIELD_STATUS, status.name)
    put(WebhookPoller.FIELD_CLAIMED_BY, claimedBy)
    put(WebhookPoller.FIELD_CLAIM_EXPIRES_AT, claimExpiresAt?.let { java.util.Date.from(it) })
    put(WebhookPoller.FIELD_ATTEMPTS, attempts)
    put(WebhookPoller.FIELD_LAST_ERROR, lastError)
    put(WebhookPoller.FIELD_CREATED_AT, java.util.Date.from(createdAt))
}

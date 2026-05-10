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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.Document
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 분산 webhook 이벤트 polling 워커.
 *
 * ## 동작/계약
 *
 * - 다중 인스턴스 환경에서 단 1개만 polling 하도록 [SuspendLeaderElector] 로 보호.
 * - 리더는 [WebhookPollerOptions.batchSize] 만큼 atomic claim 후 [handler] 실행.
 * - 비리더는 짧게 sleep 후 재시도 — 리더 사망 시 자동 인계.
 *
 * ### Claim 모델
 *
 * 1. `findOneAndUpdate` 로 PENDING 또는 만료된 CLAIMED event 를 atomic 점유.
 *    - filter: `{ attempts: { $lt: maxAttempts } }` AND
 *      (`{ status: PENDING }` OR `{ status: CLAIMED, claimExpiresAt: { $lt: now } }`)
 *    - update: `status=CLAIMED, claimedBy=nodeId, claimExpiresAt=now+claimDuration, $inc attempts: 1`
 * 2. **attempts 증가는 claim 시점 1회만**. handler 성공/실패 시 추가 증가 없음.
 * 3. claim 자체가 시도(attempt) 로 정의됨.
 * 4. `attempts >= maxAttempts` 인 expired CLAIMED 는 reclaim 안 됨 (P2-3) — 별도 sweeper 가 정리 필요.
 *
 * ### 실패 전이 (Failure Transition)
 *
 * handler 가 throw 하면, **본 인스턴스가 여전히 claim owner 인 경우에만** 상태 전이:
 * - `attempts >= maxAttempts` → `status=FAILED`, `lastError=ex.message` (DLQ 대체)
 * - 그렇지 않으면 → `status=PENDING`, `claimedBy=null`, `claimExpiresAt=null`,
 *   `lastError=ex.message` (다음 사이클에 즉시 재점유 가능)
 *
 * lease 만료로 다른 인스턴스가 reclaim 한 후 본 인스턴스가 늦게 깨어나면 (P2-2) update 는 무시되며
 * `claim ownership lost` 로그 기록.
 *
 * ### Graceful Stop
 *
 * [stopGracefully] 는 polling job 을 cancel 하고 timeout 안에 join.
 * 이미 점유 중인 event 가 있으면 cancel 시점에 `CLAIMED` 상태로 남으며,
 * `claimExpiresAt` 만료 후 다른 인스턴스가 reclaim 하여 처리한다 (at-least-once 보장).
 *
 * ### 인덱스
 *
 * 첫 [start] 호출 시 `(status, claimExpiresAt)` 복합 인덱스를 생성하여 claim 쿼리 성능 보장.
 *
 * ```kotlin
 * val elector = MongoSuspendLeaderElector(lockCollection)
 * val poller = WebhookPoller(elector, eventCollection, options) { event ->
 *     httpClient.post(targetUrl, event.payload)   // 외부로 webhook 전달
 * }
 * val job = poller.start(applicationScope)
 * // ... shutdown ...
 * poller.stopGracefully()
 * ```
 *
 * @param elector 외부에서 주입된 [SuspendLeaderElector] (테스트 편의 + 백엔드 교체 가능)
 * @param eventCollection [WebhookEvent] document 가 저장된 collection (lock collection 과 분리)
 * @param options 폴링 동작 설정
 * @param handler event 처리 콜백. 예외는 격리되어 다음 event 처리는 계속.
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
     * [start] / [stopGracefully] 의 동시 호출을 직렬화하기 위한 락.
     *
     * 가상 스레드 환경에서도 안전하도록 `synchronized` 가 아닌 [ReentrantLock] 을 사용한다.
     */
    private val lifecycleLock = ReentrantLock()

    @Volatile
    private var pollerJob: Job? = null

    @Volatile
    private var indexEnsured: Boolean = false

    /**
     * 폴링 루프를 시작하고 [Job] 을 반환한다.
     *
     * - 동일 인스턴스에서 두 번 호출 금지 (이미 실행 중이면 [IllegalStateException]).
     * - [scope] 가 cancel 되면 자동 종료.
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
     * 폴링 루프를 정상 종료한다. [timeout] 안에 종료되지 않으면 강제 cancel 후 반환.
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
            // 인덱스 생성 실패는 fatal 이 아님 — 성능 저하는 있을 수 있음
            log.warn(e) { "[${options.nodeId}] webhook event 인덱스 생성 실패 — collection scan 으로 동작" }
            indexEnsured = true
        }
    }

    private suspend fun runLoop() {
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            try {
                elector.runIfLeader(options.lockName) {
                    log.debug { "[${options.nodeId}] 리더 선출 — batch 처리 시작" }
                    processBatch()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[${options.nodeId}] 리더 동작 중 예외 — 다음 사이클 재시도" }
                null
            }

            // 리더/비리더 모두 동일 사이클 휴지 — leader-lock 자체의 waitTime 은 elector 옵션에서 관리
            delay(options.pollInterval)
        }
    }

    /**
     * 단일 batch 처리. 최대 [WebhookPollerOptions.batchSize] 개 event 를 claim & process.
     * @return 처리된 event 수
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
     * Atomic claim — `findOneAndUpdate` 로 PENDING 또는 만료된 CLAIMED event 를 점유.
     * @return 점유 성공 시 [WebhookEvent], 처리 가능한 event 가 없으면 null
     */
    private suspend fun claimNext(): WebhookEvent? {
        val now = Instant.now()
        val claimExpiresAt = now.plusMillis(options.claimDuration.inWholeMilliseconds)

        // P2-3: attempts >= maxAttempts 인 expired CLAIMED event 는 reclaim 안 함
        // (orphan CLAIMED 가 maxAttempts 직전에 동결될 수 있으나 별도 sweeper 가 정리 — README 참조)
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
            log.warn(e) { "[${options.nodeId}] claimNext 실패 — 다음 사이클 재시도" }
            return null
        }

        return updated?.toWebhookEvent()
    }

    /**
     * 단일 event 처리. handler 예외는 격리되어 다음 event 진행에 영향 없음.
     */
    private suspend fun handleSingle(event: WebhookEvent) {
        try {
            handler(event)
            markDone(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] handler 실패 eventId=${event.eventId} attempts=${event.attempts}" }
            markFailureOrRequeue(event, e)
        }
    }

    private suspend fun markDone(event: WebhookEvent) {
        try {
            // P2-2: claim 소유권 검증 — stale owner 의 update 가 새 owner 의 CLAIMED 를 덮어쓰지 않도록
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
            log.warn(e) { "[${options.nodeId}] markDone 실패 eventId=${event.eventId}" }
        }
    }

    private suspend fun markFailureOrRequeue(event: WebhookEvent, ex: Exception) {
        // attempts 는 claim 시점에 이미 +1 되어 event 에 반영되어 있음
        val errorMessage = ex.message ?: ex::class.qualifiedName ?: "unknown"
        try {
            // P2-2: claim 소유권 검증 — stale owner 가 새 owner 의 CLAIMED 를 덮어쓰지 않도록
            val ownership = ownedClaimFilter(event)
            val result = if (event.attempts >= options.maxAttempts) {
                // DLQ 대체 — FAILED 로 종결
                eventCollection.updateOne(
                    ownership,
                    Updates.combine(
                        Updates.set(FIELD_STATUS, WebhookEventStatus.FAILED.name),
                        Updates.set(FIELD_LAST_ERROR, errorMessage),
                    ),
                )
            } else {
                // 재시도 가능 — PENDING 으로 되돌림
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
                    "[${options.nodeId}] markFailureOrRequeue — claim ownership lost eventId=${event.eventId} (skip update)"
                }
            } else if (event.attempts >= options.maxAttempts) {
                log.info { "[${options.nodeId}] eventId=${event.eventId} FAILED (maxAttempts=${options.maxAttempts} 도달)" }
            } else {
                log.debug {
                    "[${options.nodeId}] eventId=${event.eventId} PENDING 으로 복귀 (attempts=${event.attempts}/${options.maxAttempts})"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] markFailureOrRequeue 실패 eventId=${event.eventId}" }
        }
    }

    /**
     * P2-2: claim 소유권 검증 filter.
     *
     * 본 인스턴스가 claim 시점에 기록한 `(eventId, status=CLAIMED, claimedBy=nodeId, claimExpiresAt)` 4-튜플이
     * 그대로 유지되어 있는 document 만 매칭. 다른 인스턴스가 reclaim 한 경우 `claimExpiresAt` 또는 `claimedBy` 가
     * 달라져 매칭 실패 → matchedCount=0.
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

/** Mongo [Document] → [WebhookEvent] 변환. */
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

/** [WebhookEvent] → Mongo [Document] 변환 (insert 시 사용). */
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

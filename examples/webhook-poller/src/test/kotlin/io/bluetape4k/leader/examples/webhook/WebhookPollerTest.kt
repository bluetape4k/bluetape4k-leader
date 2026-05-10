package io.bluetape4k.leader.examples.webhook

import com.mongodb.client.model.Filters
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [WebhookPoller] 통합 테스트 — 실시간 Mongo Testcontainers 기반.
 */
class WebhookPollerTest: AbstractWebhookPollerTest() {

    companion object: KLogging() {
        private val INSTANCE_TIMEOUT = 30.seconds
    }

    private fun fastOptions(
        nodeId: String,
        lockName: String,
        maxAttempts: Int = 3,
        claimDuration: Duration = 2.seconds,
    ) = WebhookPollerOptions(
        nodeId = nodeId,
        lockName = lockName,
        pollInterval = 50.milliseconds,
        batchSize = 10,
        maxAttempts = maxAttempts,
        claimDuration = claimDuration,
    )

    private suspend fun waitUntil(
        timeout: Duration = 10.seconds,
        condition: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50.milliseconds)
        }
        return false
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `단일 인스턴스 - 모든 PENDING event 처리 후 DONE`() = runBlocking {
        val lockName = randomLockName()
        val eventIds = (1..5).map { randomEventId() }
        eventIds.forEach { insertPending(it, payload = "p-$it") }

        val handled = ConcurrentHashMap.newKeySet<String>()
        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-1", lockName),
        ) { event -> handled.add(event.eventId) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            poller.start(scope)

            val ok = waitUntil(INSTANCE_TIMEOUT) {
                eventCollection.countDocuments(
                    Filters.eq(WebhookPoller.FIELD_STATUS, WebhookEventStatus.DONE.name),
                ) == eventIds.size.toLong()
            }
            ok shouldBeEqualTo true
            handled.size shouldBeEqualTo eventIds.size
            eventIds.forEach { id -> handled.contains(id) shouldBeEqualTo true }
        } finally {
            poller.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `3 인스턴스 동시 - 동일 event 중복 처리 없음`() = runBlocking {
        val lockName = randomLockName()
        val eventIds = (1..15).map { randomEventId() }
        eventIds.forEach { insertPending(it, payload = "p-$it") }

        // eventId 별 처리 카운터 — 정확히 1번이어야 함
        val processCount = ConcurrentHashMap<String, AtomicInteger>()
        val handlerNodeIds = ConcurrentHashMap<String, String>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pollers = (1..3).map { idx ->
            val nodeId = "node-$idx"
            val elector = newElector()
            WebhookPoller(
                elector = elector,
                eventCollection = eventCollection,
                options = fastOptions(nodeId, lockName, claimDuration = 5.seconds),
            ) { event ->
                processCount.computeIfAbsent(event.eventId) { AtomicInteger(0) }.incrementAndGet()
                handlerNodeIds[event.eventId] = nodeId
                delay(20.milliseconds)
            }
        }

        try {
            pollers.forEach { it.start(scope) }
            val ok = waitUntil(INSTANCE_TIMEOUT) {
                eventCollection.countDocuments(
                    Filters.eq(WebhookPoller.FIELD_STATUS, WebhookEventStatus.DONE.name),
                ) == eventIds.size.toLong()
            }
            ok shouldBeEqualTo true

            // 모든 event 가 정확히 1번씩 처리됨
            processCount.size shouldBeEqualTo eventIds.size
            processCount.values.forEach { it.get() shouldBeEqualTo 1 }
        } finally {
            pollers.forEach { it.stopGracefully(2.seconds) }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `handler 예외 - attempts 증가 후 다음 사이클에 재처리`(): Unit = runBlocking {
        val lockName = randomLockName()
        val eventId = randomEventId()
        insertPending(eventId, payload = "retry")

        val attemptCounter = AtomicInteger(0)
        val firstAttemptDone = CompletableDeferred<Unit>()
        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-retry", lockName, maxAttempts = 5, claimDuration = 1.seconds),
        ) { _ ->
            val n = attemptCounter.incrementAndGet()
            if (n == 1) {
                firstAttemptDone.complete(Unit)
                throw IllegalStateException("first attempt fails")
            }
            // 두 번째는 성공
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            poller.start(scope)

            // 첫 시도 실패까지 대기
            withTimeoutOrNull(INSTANCE_TIMEOUT) { firstAttemptDone.await() }.shouldNotBeNull()

            val ok = waitUntil(INSTANCE_TIMEOUT) {
                fetchEvent(eventId)?.status == WebhookEventStatus.DONE
            }
            ok shouldBeEqualTo true
            attemptCounter.get() shouldBeGreaterOrEqualTo 2
            val finalEvent = fetchEvent(eventId).shouldNotBeNull()
            finalEvent.status shouldBeEqualTo WebhookEventStatus.DONE
            finalEvent.attempts shouldBeGreaterOrEqualTo 2
        } finally {
            poller.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `maxAttempts 도달 - event FAILED 로 종결`(): Unit = runBlocking {
        val lockName = randomLockName()
        val eventId = randomEventId()
        insertPending(eventId, payload = "always-fail")
        val maxAttempts = 3
        val attemptCounter = AtomicInteger(0)

        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-fail", lockName, maxAttempts = maxAttempts, claimDuration = 500.milliseconds),
        ) { _ ->
            attemptCounter.incrementAndGet()
            throw IllegalStateException("permanent failure")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            poller.start(scope)
            val ok = waitUntil(INSTANCE_TIMEOUT) {
                fetchEvent(eventId)?.status == WebhookEventStatus.FAILED
            }
            ok shouldBeEqualTo true
            val finalEvent = fetchEvent(eventId).shouldNotBeNull()
            finalEvent.status shouldBeEqualTo WebhookEventStatus.FAILED
            finalEvent.attempts shouldBeEqualTo maxAttempts
            finalEvent.lastError.shouldNotBeNull()
        } finally {
            poller.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `리더 cancel - 차순위 인스턴스가 인계하여 처리 완료`(): Unit = runBlocking {
        val lockName = randomLockName()
        val eventIds = (1..5).map { randomEventId() }
        eventIds.forEach { insertPending(it) }

        val handled = ConcurrentHashMap.newKeySet<String>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val electorA = newElector()
        val electorB = newElector()
        val pollerA = WebhookPoller(
            elector = electorA,
            eventCollection = eventCollection,
            options = fastOptions("node-A", lockName, claimDuration = 1.seconds),
        ) { event ->
            handled.add(event.eventId)
            delay(50.milliseconds)
        }
        val pollerB = WebhookPoller(
            elector = electorB,
            eventCollection = eventCollection,
            options = fastOptions("node-B", lockName, claimDuration = 1.seconds),
        ) { event ->
            handled.add(event.eventId)
            delay(50.milliseconds)
        }

        try {
            pollerA.start(scope)
            pollerB.start(scope)

            // 일부 처리되었을 때 A 를 graceful stop
            waitUntil(5.seconds) { handled.size >= 1 }
            pollerA.stopGracefully(2.seconds)

            // 나머지 처리 — B 가 인계
            val ok = waitUntil(INSTANCE_TIMEOUT) {
                eventCollection.countDocuments(
                    Filters.eq(WebhookPoller.FIELD_STATUS, WebhookEventStatus.DONE.name),
                ) == eventIds.size.toLong()
            }
            ok shouldBeEqualTo true
        } finally {
            pollerB.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `blank nodeId - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            WebhookPollerOptions(nodeId = " ", lockName = "ok")
        }
    }

    @Test
    fun `blank lockName - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            WebhookPollerOptions(nodeId = "ok", lockName = " ")
        }
    }

    @Test
    fun `non-positive batchSize - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            WebhookPollerOptions(nodeId = "ok", lockName = "ok", batchSize = 0)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `start 후 scope cancel - poller 자동 종료 (CancellationException 재throw)`(): Unit = runBlocking {
        val lockName = randomLockName()
        insertPending(randomEventId())

        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-cancel", lockName),
        ) { delay(10.milliseconds) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = poller.start(scope)
        delay(200.milliseconds)
        job.cancelAndJoin()
        job.isCancelled shouldBeEqualTo true
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `동시 start - 하나만 성공하고 나머지는 이미 실행 중으로 실패`() = runBlocking {
        val lockName = randomLockName()
        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-concurrent-start", lockName),
        ) { delay(10.milliseconds) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = Executors.newFixedThreadPool(16)
        val startGate = CountDownLatch(1)
        try {
            val attempts = (1..32).map {
                executor.submit<Result<Unit>> {
                    startGate.await()
                    runCatching {
                        poller.start(scope)
                        Unit
                    }
                }
            }

            startGate.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is IllegalStateException } shouldBeEqualTo 31
        } finally {
            executor.shutdownNow()
            poller.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    /**
     * P2-3: lease 만료 reclaim 시나리오.
     *
     * - node-A 가 event 를 claim 하지만 handler 실행 중 사망(cancel) → CLAIMED 상태 유지
     * - claimDuration 만료 후 node-B 가 동일 event 를 reclaim 하여 처리 완료
     * - at-least-once 보장 검증
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `leader 가 claim 후 사망 시 - 차순위가 같은 event 처리 (lease 만료 reclaim)`(): Unit = runBlocking {
        val lockName = randomLockName()
        val eventId = randomEventId()
        insertPending(eventId, payload = "reclaim-target")

        val handlerStarted = CompletableDeferred<Unit>()
        val handledByB = CompletableDeferred<Unit>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val electorA = newElector()
        val pollerA = WebhookPoller(
            elector = electorA,
            eventCollection = eventCollection,
            // 매우 짧은 claim — A 사망 후 즉시 reclaim 가능
            options = fastOptions("node-A", lockName, claimDuration = 500.milliseconds),
        ) { _ ->
            handlerStarted.complete(Unit)
            // 영원히 처리 안 함 (cancel 시뮬레이션)
            delay(60.seconds)
        }
        val electorB = newElector()
        val pollerB = WebhookPoller(
            elector = electorB,
            eventCollection = eventCollection,
            options = fastOptions("node-B", lockName, claimDuration = 500.milliseconds),
        ) { event ->
            if (event.eventId == eventId) handledByB.complete(Unit)
        }

        try {
            pollerA.start(scope)
            // A 가 claim & handler 진입 확인
            withTimeoutOrNull(INSTANCE_TIMEOUT) { handlerStarted.await() }.shouldNotBeNull()
            // A 사망
            pollerA.stopGracefully(1.seconds)

            // B 시작 — claim 만료 후 reclaim
            pollerB.start(scope)
            withTimeoutOrNull(INSTANCE_TIMEOUT) { handledByB.await() }.shouldNotBeNull()

            val ok = waitUntil(INSTANCE_TIMEOUT) {
                fetchEvent(eventId)?.status == WebhookEventStatus.DONE
            }
            ok shouldBeEqualTo true
            val finalEvent = fetchEvent(eventId).shouldNotBeNull()
            finalEvent.status shouldBeEqualTo WebhookEventStatus.DONE
            // attempts: A 의 claim(1) + B 의 reclaim(1) = 최소 2
            finalEvent.attempts shouldBeGreaterOrEqualTo 2
        } finally {
            pollerB.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    /**
     * P2-3: maxAttempts 도달한 expired CLAIMED event 는 reclaim 되지 않아야 한다.
     *
     * 자연스러운 흐름에선 이 상태가 거의 발생하지 않지만 (handler-throw 경로는 markFailureOrRequeue 가 정리),
     * 다음 시나리오에선 발생 가능:
     * - claim 직후 인스턴스 crash → CLAIMED 잔존 → 다음 인스턴스 reclaim → handler-throw
     * - 위 사이클이 maxAttempts 만큼 반복된 직후 또 한 번 crash → `attempts==maxAttempts` 인 expired CLAIMED 동결
     *
     * 본 테스트는 이 상태를 직접 insert 하여 폴러가 절대 reclaim 하지 않음을 검증.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `maxAttempts 도달한 expired CLAIMED event - reclaim 안 됨`(): Unit = runBlocking {
        val lockName = randomLockName()
        val eventId = randomEventId()
        val maxAttempts = 3

        // 직접 maxAttempts 도달 + expired CLAIMED state 의 document 를 삽입
        val expiredCount = AtomicInteger(0)
        val stuckEvent = WebhookEvent(
            eventId = eventId,
            payload = "stuck",
            status = WebhookEventStatus.CLAIMED,
            claimedBy = "dead-node",
            claimExpiresAt = java.time.Instant.now().minusSeconds(60),
            attempts = maxAttempts,
        )
        eventCollection.insertOne(stuckEvent.toDocument())

        // 함께 처리되는 PENDING event 도 1건 — 폴러가 살아있고 사이클 돌고 있음을 보장
        val sentinelId = randomEventId()
        insertPending(sentinelId, payload = "sentinel")

        val sentinelDone = CompletableDeferred<Unit>()
        val elector = newElector()
        val poller = WebhookPoller(
            elector = elector,
            eventCollection = eventCollection,
            options = fastOptions("node-stuck", lockName, maxAttempts = maxAttempts, claimDuration = 1.seconds),
        ) { event ->
            if (event.eventId == eventId) {
                expiredCount.incrementAndGet()    // 절대 호출되면 안 됨
            } else if (event.eventId == sentinelId) {
                sentinelDone.complete(Unit)
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            poller.start(scope)
            // sentinel 처리 완료까지 대기 — 폴러 사이클이 충분히 돌았음을 보장
            withTimeoutOrNull(INSTANCE_TIMEOUT) { sentinelDone.await() }.shouldNotBeNull()
            // 추가로 몇 사이클 더 대기 (claim 만료 충분히 경과)
            delay(2.seconds)

            // stuck event 는 절대 처리되면 안 됨
            expiredCount.get() shouldBeEqualTo 0
            // stuck event 는 CLAIMED 상태로 남아있어야 함 (다른 인스턴스가 reclaim 안 함)
            val finalEvent = fetchEvent(eventId).shouldNotBeNull()
            finalEvent.status shouldBeEqualTo WebhookEventStatus.CLAIMED
            finalEvent.claimedBy shouldBeEqualTo "dead-node"
            finalEvent.attempts shouldBeEqualTo maxAttempts
        } finally {
            poller.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }
}

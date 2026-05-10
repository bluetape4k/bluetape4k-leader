package io.bluetape4k.leader.examples.webhook

import com.mongodb.kotlin.client.coroutine.MongoClient
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document

/**
 * 3 인스턴스 동시 polling 데모.
 *
 * - 동일 MongoDB collection 을 공유하며 단 1개 인스턴스만 처리하는 시나리오 시뮬레이션.
 * - 가짜 이벤트 10건을 미리 insert 후 polling 결과를 검증.
 */
object WebhookPollerDemo: KLogging() {

    private const val DEMO_DB_NAME = "webhook_demo"
    private const val DEMO_EVENT_COLLECTION = "webhook_events"
    private const val DEMO_LOCK_COLLECTION = "webhook_locks"
    private const val DEMO_LOCK_NAME = "demo-webhook-poller"
    private const val DEMO_EVENT_COUNT = 10
    private const val DEMO_INSTANCE_COUNT = 3

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val mongoUrl = System.getenv("MONGO_URL") ?: "mongodb://localhost:27017"
        log.info { "[demo] Mongo 연결: $mongoUrl (override via MONGO_URL env var)" }
        val client = MongoClient.create(mongoUrl)
        try {
            val db = client.getDatabase(DEMO_DB_NAME)
            val eventCollection = db.getCollection<Document>(DEMO_EVENT_COLLECTION)
            val lockCollection = db.getCollection<Document>(DEMO_LOCK_COLLECTION)

            // 데모 재실행 시 unique 인덱스 충돌 방지 — 이전 데이터 정리
            eventCollection.deleteMany(Document())
            lockCollection.deleteMany(Document())

            // 가짜 이벤트 insert
            val pendingEvents = (1..DEMO_EVENT_COUNT).map { idx ->
                WebhookEvent(eventId = "evt-$idx-${UUID.randomUUID()}", payload = "payload-$idx")
            }
            eventCollection.insertMany(pendingEvents.map { it.toDocument() })
            log.info { "[demo] $DEMO_EVENT_COUNT 개 이벤트 insert 완료" }

            // 인스턴스마다 처리 카운터 (eventId 별로 정확히 1번 처리되는지 검증)
            val processedBy = ConcurrentHashMap<String, String>()
            val totalProcessed = AtomicInteger(0)

            coroutineScope {
                val pollers = (1..DEMO_INSTANCE_COUNT).map { idx ->
                    val nodeId = "node-$idx"
                    val elector = MongoSuspendLeaderElector(lockCollection)
                    val poller = WebhookPoller(
                        elector = elector,
                        eventCollection = eventCollection,
                        options = WebhookPollerOptions(
                            nodeId = nodeId,
                            lockName = DEMO_LOCK_NAME,
                            pollInterval = 200.milliseconds,
                            batchSize = 5,
                            maxAttempts = 3,
                            claimDuration = 10.seconds,
                        ),
                    ) { event ->
                        val first = processedBy.putIfAbsent(event.eventId, nodeId)
                        if (first == null) {
                            totalProcessed.incrementAndGet()
                            log.info { "[$nodeId] 처리 eventId=${event.eventId}" }
                        } else {
                            log.info { "[$nodeId] 중복 처리 감지! eventId=${event.eventId} (이미 $first 가 처리)" }
                        }
                        delay(50.milliseconds)
                    }
                    poller.start(this) to poller
                }

                // 처리 대기 — 모든 이벤트 DONE 될 때까지
                val deadline = System.currentTimeMillis() + 30_000
                while (totalProcessed.get() < DEMO_EVENT_COUNT && System.currentTimeMillis() < deadline) {
                    delay(200.milliseconds)
                }

                pollers.forEach { (_, poller) -> poller.stopGracefully(2.seconds) }
            }

            val totalDone = eventCollection.countDocuments(
                Document(WebhookPoller.FIELD_STATUS, WebhookEventStatus.DONE.name),
            )
            log.info { "[demo] === 결과 ===" }
            log.info { "[demo] DONE=$totalDone (기대값 $DEMO_EVENT_COUNT)" }
            log.info { "[demo] 처리한 인스턴스 분포=${processedBy.values.groupingBy { it }.eachCount()}" }
            log.info { "[demo] 중복 처리 없음? ${processedBy.size == DEMO_EVENT_COUNT}" }
        } finally {
            client.close()
        }
    }
}

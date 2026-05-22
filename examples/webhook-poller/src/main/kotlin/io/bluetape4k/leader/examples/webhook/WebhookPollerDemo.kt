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
 * Demo that runs three poller instances concurrently.
 *
 * - Simulates a scenario where multiple instances share the same MongoDB collection and only one
 *   instance processes each event.
 * - Inserts 10 fake events and verifies the polling result.
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
        log.info { "[demo] Mongo connection: $mongoUrl (override via MONGO_URL env var)" }
        val client = MongoClient.create(mongoUrl)
        try {
            val db = client.getDatabase(DEMO_DB_NAME)
            val eventCollection = db.getCollection<Document>(DEMO_EVENT_COLLECTION)
            val lockCollection = db.getCollection<Document>(DEMO_LOCK_COLLECTION)

            // Clear previous data so rerunning the demo does not hit unique-index conflicts.
            eventCollection.deleteMany(Document())
            lockCollection.deleteMany(Document())

            // Insert fake events.
            val pendingEvents = (1..DEMO_EVENT_COUNT).map { idx ->
                WebhookEvent(eventId = "evt-$idx-${UUID.randomUUID()}", payload = "payload-$idx")
            }
            eventCollection.insertMany(pendingEvents.map { it.toDocument() })
            log.info { "[demo] inserted $DEMO_EVENT_COUNT events" }

            // Track which instance processed each event to verify exactly-once handling per eventId.
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
                            log.info { "[$nodeId] processed eventId=${event.eventId}" }
                        } else {
                            log.info {
                                "[$nodeId] duplicate processing detected. eventId=${event.eventId} " +
                                    "(already processed by $first)"
                            }
                        }
                        delay(50.milliseconds)
                    }
                    poller.start(this) to poller
                }

                // Wait until every event is DONE.
                val deadline = System.currentTimeMillis() + 30_000
                while (totalProcessed.get() < DEMO_EVENT_COUNT && System.currentTimeMillis() < deadline) {
                    delay(200.milliseconds)
                }

                pollers.forEach { (_, poller) -> poller.stopGracefully(2.seconds) }
            }

            val totalDone = eventCollection.countDocuments(
                Document(WebhookPoller.FIELD_STATUS, WebhookEventStatus.DONE.name),
            )
            log.info { "[demo] === Result ===" }
            log.info { "[demo] DONE=$totalDone (expected $DEMO_EVENT_COUNT)" }
            log.info { "[demo] processed-by distribution=${processedBy.values.groupingBy { it }.eachCount()}" }
            log.info { "[demo] no duplicate processing? ${processedBy.size == DEMO_EVENT_COUNT}" }
        } finally {
            client.close()
        }
    }
}

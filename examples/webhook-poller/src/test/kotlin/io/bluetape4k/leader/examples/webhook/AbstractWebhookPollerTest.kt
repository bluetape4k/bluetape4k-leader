package io.bluetape4k.leader.examples.webhook

import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.utils.ShutdownQueue
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

/**
 * MongoDB Testcontainers 기반 webhook poller 테스트 베이스.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractWebhookPollerTest {

    companion object: KLogging() {
        const val EVENT_COLLECTION_NAME = "webhook_events"

        val mongoServer: MongoDBServer = MongoDBServer.Launcher.mongoDB

        val coroutineMongoClient by lazy {
            MongoDBServer.Launcher.getCoroutineClient().also {
                ShutdownQueue.register { it.close() }
            }
        }

        val coroutineDb: MongoDatabase by lazy { coroutineMongoClient.getDatabase("webhook_test") }

        val eventCollection: MongoCollection<Document> by lazy {
            coroutineDb.getCollection<Document>(EVENT_COLLECTION_NAME)
        }

        val lockCollection: MongoCollection<Document> by lazy {
            coroutineDb.getCollection<Document>(MongoLock.LOCK_COLLECTION_NAME)
        }

        fun randomEventId(): String = "evt-${Base58.randomString(8)}"
        fun randomLockName(): String = "wh-test-${Base58.randomString(8)}"
    }

    @BeforeEach
    fun cleanCollections() {
        runBlocking {
            eventCollection.deleteMany(Document())
            lockCollection.deleteMany(Document())
        }
    }

    /** 테스트용 elector — 매번 새 인스턴스 생성 (`MongoSuspendLock.ensureIndexes` suspend invoke). */
    protected suspend fun newElector(
        options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    ): MongoSuspendLeaderElector = MongoSuspendLeaderElector(lockCollection, options)

    /** 테스트용 PENDING event 1건 insert. */
    protected suspend fun insertPending(eventId: String = randomEventId(), payload: String = "payload"): WebhookEvent {
        val event = WebhookEvent(eventId = eventId, payload = payload)
        eventCollection.insertOne(event.toDocument())
        return event
    }

    protected suspend fun fetchEvent(eventId: String): WebhookEvent? {
        val doc = eventCollection.find(
            Document(WebhookPoller.FIELD_EVENT_ID, eventId),
        ).firstOrNull()
        return doc?.toWebhookEvent()
    }
}

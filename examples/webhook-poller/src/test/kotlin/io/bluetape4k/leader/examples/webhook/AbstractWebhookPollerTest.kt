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
 * `AbstractWebhookPollerTest`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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

    /**
     * `newElector` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    protected suspend fun newElector(
        options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    ): MongoSuspendLeaderElector = MongoSuspendLeaderElector(lockCollection, options)

    /**
     * `insertPending` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
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

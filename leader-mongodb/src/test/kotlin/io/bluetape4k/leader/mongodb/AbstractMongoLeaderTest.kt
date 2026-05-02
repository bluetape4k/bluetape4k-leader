package io.bluetape4k.leader.mongodb

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.utils.ShutdownQueue
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

/**
 * MongoDB 기반 리더 선출 테스트의 기반 클래스입니다.
 *
 * [MongoDBServer.Launcher.mongoDB] 로 격리된 MongoDB 인스턴스를 시작하고,
 * 동기/코루틴 클라이언트와 컬렉션 참조를 제공합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractMongoLeaderTest {

    companion object : KLogging() {
        val mongoServer: MongoDBServer = MongoDBServer.Launcher.mongoDB

        val mongoClient by lazy {
            MongoDBServer.Launcher.getClient().also {
                ShutdownQueue.register { it.close() }
            }
        }

        val coroutineMongoClient by lazy {
            MongoDBServer.Launcher.getCoroutineClient().also {
                ShutdownQueue.register { it.close() }
            }
        }

        val db by lazy { mongoClient.getDatabase("leader_test") }
        val coroutineDb by lazy { coroutineMongoClient.getDatabase("leader_test") }

        val lockCollection by lazy { db.getCollection(MongoLock.LOCK_COLLECTION_NAME) }
        val groupLockCollection by lazy { db.getCollection(MongoLock.GROUP_LOCK_COLLECTION_NAME) }

        val coroutineLockCollection by lazy {
            coroutineDb.getCollection<Document>(MongoLock.LOCK_COLLECTION_NAME)
        }
        val coroutineGroupLockCollection by lazy {
            coroutineDb.getCollection<Document>(MongoLock.GROUP_LOCK_COLLECTION_NAME)
        }

        fun randomName(): String = "test-${Base58.randomString(8)}"
    }

    @BeforeEach
    fun cleanCollections() {
        lockCollection.deleteMany(Document())
        groupLockCollection.deleteMany(Document())
    }
}

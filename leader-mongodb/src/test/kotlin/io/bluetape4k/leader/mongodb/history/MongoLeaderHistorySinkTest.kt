package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.InsertOneOptions
import com.mongodb.client.result.InsertOneResult
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class MongoLeaderHistorySinkTest {

    companion object: KLogging()

    private val database = mockk<MongoDatabase>(relaxed = true)
    private val collection = mockk<MongoCollection<Document>>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(database, collection)
    }

    @Test
    fun `history ID는 bluetape4k UUID v7 문자열을 사용하고 중복되지 않는다`() = runTest {
        every { database.getCollection<Document>(any()) } returns collection
        coEvery {
            collection.insertOne(any<Document>(), any<InsertOneOptions>())
        } returns mockk<InsertOneResult>()

        val sink = MongoLeaderHistorySink(database)
        val keys = List(32) { index ->
            sink.recordAcquired(
                LeaderLockHistoryRecord(
                    lockName = "history-lock-$index",
                    token = "token-$index",
                    kind = LockIdentity.AnnotationKind.SINGLE,
                    acquiredAt = Instant.EPOCH,
                    lockedUntil = Instant.EPOCH.plusSeconds(60),
                    nodeId = "node-1",
                ),
            ).shouldNotBeNull()
        }

        val historyIds = keys.map { it.historyId.shouldNotBeNull() }
        historyIds.toSet().size shouldBeEqualTo historyIds.size
        historyIds.forEach { historyId ->
            UUID.fromString(historyId).version() shouldBeEqualTo 7
        }
    }
}

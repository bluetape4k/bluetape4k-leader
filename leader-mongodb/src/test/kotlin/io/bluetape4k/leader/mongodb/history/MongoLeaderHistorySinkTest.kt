package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertOneOptions
import com.mongodb.client.result.InsertOneResult
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MongoLeaderHistorySinkTest {

    @Test
    fun `history ID는 bluetape4k UUID v7 문자열을 사용하고 중복되지 않는다`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        coEvery { collection.insertOne(any<Document>(), any<InsertOneOptions>()) } returns mockk<InsertOneResult>()

        val sink = MongoLeaderHistorySink(database)
        val keys = (1..32).map { index ->
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

class MongoLeaderHistorySinkIntegrationTest : AbstractMongoLeaderTest() {

    @Test
    fun `MongoDB history document는 canonical UUID와 update 경로를 보존한다`() = runSuspendIO {
        val config = MongoHistoryConfig(collectionName = "history-${randomName()}", ttlDays = 0)
        val collection = coroutineDb.getCollection<Document>(config.collectionName)
        val sink = MongoLeaderHistorySink(coroutineDb, config)
        val record = LeaderLockHistoryRecord(
            lockName = "history-integration-lock",
            token = "history-integration-token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = Instant.EPOCH,
            lockedUntil = Instant.EPOCH.plusSeconds(60),
            nodeId = "node-1",
        )

        try {
            val key = sink.recordAcquired(record).shouldNotBeNull()
            val historyId = key.historyId.shouldNotBeNull()
            UUID.fromString(historyId).version() shouldBeEqualTo 7

            val acquired = collection.find(Filters.eq("historyId", historyId)).first()
            acquired.getString("historyId") shouldBeEqualTo historyId
            acquired.getString("status") shouldBeEqualTo LeaderHistoryStatus.ACQUIRED.name

            sink.recordCompleted(key, Instant.EPOCH.plusSeconds(1), 1_000L)
            val completed = collection.find(Filters.eq("historyId", historyId)).first()
            completed.getString("status") shouldBeEqualTo LeaderHistoryStatus.COMPLETED.name
            completed.getLong("durationMs") shouldBeEqualTo 1_000L
        } finally {
            collection.drop()
        }
    }
}

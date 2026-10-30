package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.Filters
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.first
import org.bson.Document
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class MongoLeaderHistorySinkIntegrationTest: AbstractMongoLeaderTest() {

    companion object: KLogging()

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
            lockedUntil = Instant.EPOCH + 60.seconds(),
            nodeId = "node-1",
        )
        log.debug { "record=$record" }

        try {
            val key = sink.recordAcquired(record).shouldNotBeNull()
            val historyId = key.historyId.shouldNotBeNull()
            UUID.fromString(historyId).version() shouldBeEqualTo 7

            val acquired = collection.find(Filters.eq("historyId", historyId)).first()
            log.debug { "acquired=$acquired" }
            acquired.getString("historyId") shouldBeEqualTo historyId
            acquired.getString("status") shouldBeEqualTo LeaderHistoryStatus.ACQUIRED.name

            sink.recordCompleted(key, Instant.EPOCH + 1.seconds(), 1_000L)
            val completed = collection.find(Filters.eq("historyId", historyId)).first()
            log.debug { "completed=$completed" }
            completed.getString("status") shouldBeEqualTo LeaderHistoryStatus.COMPLETED.name
            completed.getLong("durationMs") shouldBeEqualTo 1_000L
        } finally {
            collection.drop()
        }
    }
}

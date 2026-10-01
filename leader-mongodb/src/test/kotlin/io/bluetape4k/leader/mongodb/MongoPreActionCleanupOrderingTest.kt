package io.bluetape4k.leader.mongodb

import com.mongodb.MongoClientSettings
import com.mongodb.MongoNamespace
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.result.DeleteResult
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.time.Duration.Companion.seconds

class MongoPreActionCleanupOrderingTest {

    companion object: KLogging()

    private val collection = mockk<MongoCollection<Document>>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(collection)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `action 제출 실패는 single과 group 모두 정리 완료를 기다린다`(group: Boolean) {
        every { collection.namespace } returns MongoNamespace("leader-test", Base58.randomString(12))
        every { collection.createIndex(any<Bson>(), any<IndexOptions>()) } returns "expireAt_1"

        val acquired = CountDownLatch(1)
        val allowAcquisition = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)

        every {
            collection.findOneAndUpdate(any<Bson>(), any<Bson>(), any<FindOneAndUpdateOptions>())
        } answers {
            acquired.countDown()
            allowAcquisition.await(5.seconds).shouldBeTrue()
            val update = secondArg<Bson>().toBsonDocument(Document::class.java, collectionCodecRegistry)
            Document("token", update.getDocument($$"$set").getString("token").value)
        }
        every { collection.deleteOne(any<Bson>()) } answers {
            cleanupStarted.countDown()
            allowCleanup.await(5.seconds).shouldBeTrue()
            DeleteResult.acknowledged(1)
        }

        val rejection = RejectedExecutionException("action submission rejected")
        val executor = Executor { throw rejection }
        val action: () -> CompletableFuture<String> = { error("action must not run") }
        try {
            val result = if (group) {
                MongoLeaderGroupElector(
                    collection,
                    MongoLeaderGroupElectionOptions(
                        leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1),
                    ),
                ).runAsyncIfLeader("job", executor, action)
            } else {
                MongoLeaderElector(collection).runAsyncIfLeader("job", executor, action)
            }
            acquired.await(5.seconds).shouldBeTrue()
            allowAcquisition.countDown()
            cleanupStarted.await(5.seconds).shouldBeTrue()
            result.isDone.shouldBeFalse()
            allowCleanup.countDown()

            val failure = assertFailsWith<ExecutionException> {
                result.get(5.seconds)
            }
            failure.cause shouldBe rejection
            verify(exactly = 1) { collection.deleteOne(any<Bson>()) }
        } finally {
            allowAcquisition.countDown()
            allowCleanup.countDown()
        }
    }

    private val collectionCodecRegistry = MongoClientSettings.getDefaultCodecRegistry()
}

package io.bluetape4k.leader.mongodb.lock

import com.mongodb.MongoNamespace
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.result.DeleteResult
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MongoLockAsyncCancellationTest {

    @Test
    fun `취소된 async acquisition이 늦게 획득한 lock을 반납한다`() {
        val collection = mockCollection()
        val lock = MongoLock(collection, "cancel-late", 5.seconds)
        val acquisitionStarted = CountDownLatch(1)
        val releaseAcquisition = CountDownLatch(1)
        val unlockObserved = CountDownLatch(1)

        every {
            collection.findOneAndUpdate(any<Bson>(), any<Bson>(), any<FindOneAndUpdateOptions>())
        } answers {
            acquisitionStarted.countDown()
            releaseAcquisition.await(2, TimeUnit.SECONDS)
            Document("token", lock.token)
        }
        every { collection.deleteOne(any<Bson>()) } answers {
            unlockObserved.countDown()
            DeleteResult.acknowledged(1L)
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = lock.tryLockAsync(10.seconds, 10.seconds, executor)
            acquisitionStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()

            result.cancel(false).shouldBeTrue()
            releaseAcquisition.countDown()

            unlockObserved.await(2, TimeUnit.SECONDS).shouldBeTrue()
            verify(exactly = 1) { collection.deleteOne(any<Bson>()) }
        } finally {
            releaseAcquisition.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `취소된 async acquisition은 retry를 시작하지 않는다`() {
        val collection = mockCollection()
        val lock = MongoLock(collection, "cancel-retry", 5.seconds)
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val submittedTasks = LinkedBlockingQueue<Runnable>()

        every {
            collection.findOneAndUpdate(any<Bson>(), any<Bson>(), any<FindOneAndUpdateOptions>())
        } answers {
            attempts.incrementAndGet()
            firstAttempt.countDown()
            releaseFirstAttempt.await(2, TimeUnit.SECONDS)
            null
        }

        val executor = Executor { submittedTasks.add(it) }
        try {
            val result = lock.tryLockAsync(10.seconds, 1.milliseconds, executor)
            val firstTask = submittedTasks.poll(2, TimeUnit.SECONDS)
                ?: error("first acquisition task was not submitted")
            val worker = Thread(firstTask::run, "mongo-acquisition-test")
            worker.start()

            firstAttempt.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            releaseFirstAttempt.countDown()
            worker.join(2_000)

            submittedTasks.poll(500, TimeUnit.MILLISECONDS) shouldBeEqualTo null
            attempts.get() shouldBeEqualTo 1
        } finally {
            releaseFirstAttempt.countDown()
        }
    }

    private fun mockCollection(): MongoCollection<Document> {
        val collection = mockk<MongoCollection<Document>>()
        every { collection.namespace } returns MongoNamespace("leader-test", "locks-${System.nanoTime()}")
        every { collection.createIndex(any<Bson>(), any<IndexOptions>()) } returns "expireAt_1"
        return collection
    }
}

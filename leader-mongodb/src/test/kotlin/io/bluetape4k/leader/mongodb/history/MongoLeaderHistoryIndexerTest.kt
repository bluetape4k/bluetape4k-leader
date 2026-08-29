package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.IndexOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MongoLeaderHistoryIndexerTest {

    @Test
    fun `정상 close는 index build 완료 후 반환한다`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } returns "index"

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0))
        try {
            awaitIndexState(indexer, 1)
            indexer.close()
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.READY
            indexer.indexState shouldBeEqualTo 1
        } finally {
            indexer.close()
        }
    }

    @Test
    fun `동기 close bridge는 caller interrupt를 삼키지 않는다`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } coAnswers {
            started.countDown()
            release.await()
            "index"
        }

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0))
        try {
            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val thrown = AtomicReference<Throwable?>()
            val closer = thread(start = true, name = "history-indexer-close-test") {
                Thread.currentThread().interrupt()
                thrown.set(assertFailsWith<InterruptedException> { indexer.close() })
            }

            closer.join()
            release.complete(Unit)
            indexer.close()

            thrown.get().shouldBeInstanceOf<InterruptedException>()
        } finally {
            release.complete(Unit)
            indexer.close()
        }
    }

    @Test
    fun `suspend close는 caller cancellation을 전파한다`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
            }
            "index"
        }

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0))
        try {
            started.await()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) {
                    indexer.closeSuspend()
                }
            }
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.BUILDING
            indexer.indexState shouldBeEqualTo 0
        } finally {
            release.complete(Unit)
            indexer.close()
        }
    }

    @Test
    fun `shutdown timeout은 양수여야 한다`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } returns "index"

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0))
        try {
            awaitIndexState(indexer, MongoLeaderHistoryIndexState.READY.metricValue)

            listOf(0L, -1L).forEach { invalidTimeoutMs ->
                assertFailsWith<IllegalArgumentException> {
                    indexer.closeSuspend(shutdownTimeoutMs = invalidTimeoutMs)
                }
            }
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.READY
        } finally {
            indexer.close()
        }
    }

    @Test
    fun `suspend close는 내부 shutdown timeout 후 반환한다`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)
        val registry = SimpleMeterRegistry()
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
            }
            completed.set(true)
            "index"
        }

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0), registry)
        try {
            started.await()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                indexer.closeSuspend(shutdownTimeoutMs = 10)
            }
            completed.get().shouldBeFalse()
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.SHUTDOWN_TIMEOUT
            indexer.indexState shouldBeEqualTo -2
            registry.get("leader.history.mongodb.index.state").gauge().value() shouldBeEqualTo -2.0

            withContext(Dispatchers.Default.limitedParallelism(1)) {
                indexer.closeSuspend(shutdownTimeoutMs = 10)
            }
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.SHUTDOWN_TIMEOUT
            indexer.indexState shouldBeEqualTo -2

            release.complete(Unit)
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(1_000) {
                    while (!completed.get()) {
                        delay(10)
                    }
                }
            }
            completed.get().shouldBeTrue()
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.SHUTDOWN_TIMEOUT
            indexer.indexState shouldBeEqualTo -2
        } finally {
            release.complete(Unit)
            indexer.close()
            registry.close()
        }
    }

    @Test
    fun `shutdown timeout 이후 새 indexer는 독립적으로 ready 상태가 된다`() = runTest {
        val blockedDatabase = mockk<MongoDatabase>()
        val blockedCollection = mockk<MongoCollection<Document>>()
        every { blockedDatabase.getCollection<Document>(any()) } returns blockedCollection
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { blockedCollection.createIndex(any<Bson>(), any<IndexOptions>()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
            }
            "index"
        }

        val timedOutIndexer = MongoLeaderHistoryIndexer(blockedDatabase, MongoHistoryConfig(ttlDays = 0))
        try {
            started.await()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                timedOutIndexer.closeSuspend(shutdownTimeoutMs = 10)
            }
            timedOutIndexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.SHUTDOWN_TIMEOUT

            val restartedDatabase = mockk<MongoDatabase>()
            val restartedCollection = mockk<MongoCollection<Document>>()
            every { restartedDatabase.getCollection<Document>(any()) } returns restartedCollection
            coEvery { restartedCollection.createIndex(any<Bson>(), any<IndexOptions>()) } returns "index"

            val restartedIndexer = MongoLeaderHistoryIndexer(restartedDatabase, MongoHistoryConfig(ttlDays = 0))
            try {
                awaitIndexState(restartedIndexer, MongoLeaderHistoryIndexState.READY.metricValue)
                restartedIndexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.READY
            } finally {
                restartedIndexer.close()
            }
        } finally {
            release.complete(Unit)
            timedOutIndexer.close()
        }
    }

    @Test
    fun `index build failure is observable through indexState`() = runTest {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection<Document>(any()) } returns collection
        coEvery { collection.createIndex(any<Bson>(), any<IndexOptions>()) } throws
            IllegalStateException("index service unavailable")

        val indexer = MongoLeaderHistoryIndexer(database, MongoHistoryConfig(ttlDays = 0))
        try {
            awaitIndexState(indexer, -1)
            indexer.indexLifecycleState shouldBeEqualTo MongoLeaderHistoryIndexState.FAILED
            indexer.indexState shouldBeEqualTo -1
        } finally {
            indexer.close()
        }
    }

    private suspend fun awaitIndexState(indexer: MongoLeaderHistoryIndexer, expected: Int) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (indexer.indexState != expected) {
                    delay(25)
                }
            }
        }
    }
}

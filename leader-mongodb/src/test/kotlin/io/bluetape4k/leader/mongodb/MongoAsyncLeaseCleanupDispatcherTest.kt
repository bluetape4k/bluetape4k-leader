package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MongoAsyncLeaseCleanupDispatcherTest {

    @Test
    fun `caller cancellation thread에서 blocking cleanup을 실행하지 않는다`() {
        val source = CompletableFuture<String>()
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupThread = AtomicReference<String>()
        val cleanupCalls = AtomicInteger()
        val result = AsyncLeaseCleanupDispatcher.completeAfter(
            source = source,
            cleanup = {
                cleanupCalls.incrementAndGet()
                cleanupThread.set(Thread.currentThread().name)
                cleanupStarted.countDown()
                releaseCleanup.await(2, TimeUnit.SECONDS).shouldBeTrue()
            },
            transform = { value, _ -> value },
        )
        val caller = Thread {
            source.cancel(false).shouldBeTrue()
        }.apply { name = "mongo-caller" }

        try {
            caller.start()
            cleanupStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            caller.join(1_000)
            caller.isAlive.shouldBeFalse()
            (cleanupThread.get() == "mongo-caller").shouldBeFalse()
            result.isDone.shouldBeFalse()

            releaseCleanup.countDown()
            result.join() shouldBeEqualTo null
            cleanupCalls.get() shouldBeEqualTo 1
        } finally {
            releaseCleanup.countDown()
            caller.join(1_000)
        }
    }
}

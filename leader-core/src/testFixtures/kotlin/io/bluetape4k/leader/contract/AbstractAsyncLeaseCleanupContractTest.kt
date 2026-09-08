package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** backend별 dispatcher가 같은 완료·취소·실패 계약을 지키는지 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAsyncLeaseCleanupContractTest {
    /** null executor는 backend가 소유한 기본 실행기를 선택합니다. */
    protected abstract fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        executor: Executor? = null,
        cleanup: () -> Unit,
        fallbackExecutor: Executor? = null,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R>


    @Test
    fun `cleanup 오류는 원래 action 오류에 suppressed로 보존한다`() {
        val original = IllegalArgumentException("action failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val result = completeAfter(
            source = CompletableFuture.failedFuture<String>(original),
            cleanup = { throw cleanupFailure },
            transform = { value, _ -> value },
        )
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === original).shouldBeTrue()
        original.suppressed.toList() shouldBeEqualTo listOf(cleanupFailure)
    }

    @Test
    fun `action 성공 후 이중 scheduler 실패는 primary 오류로 완료한다`() {
        val primary = IllegalStateException("primary failed")
        val fallback = IllegalStateException("fallback failed")
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            executor = Executor { throw primary },
            cleanup = { error("cleanup must not run") },
            fallbackExecutor = Executor { throw fallback },
            transform = { value, _ -> value },
        )
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === primary).shouldBeTrue()
        primary.suppressed.toList() shouldBeEqualTo listOf(fallback)
    }

    @Test
    fun `두 scheduler 실패는 inline 정리 없이 원래 오류를 보존한다`() {
        val original = IllegalArgumentException("action failed")
        val primary = IllegalStateException("primary failed")
        val fallback = IllegalStateException("fallback failed")
        val calls = AtomicInteger()
        val result = completeAfter(
            source = CompletableFuture.failedFuture<String>(original),
            executor = Executor { throw primary },
            cleanup = { calls.incrementAndGet() },
            fallbackExecutor = Executor { throw fallback },
            transform = { value, _ -> value },
        )
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === original).shouldBeTrue()
        original.suppressed.toList() shouldBeEqualTo listOf(primary, fallback)
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `동일한 오류 객체가 action과 scheduler에서 발생해도 결과를 완료한다`() {
        val original = IllegalStateException("shared failure")
        val result = completeAfter(
            source = CompletableFuture.failedFuture<String>(original),
            executor = Executor { throw original },
            cleanup = { error("cleanup must not run") },
            fallbackExecutor = Executor { throw original },
            transform = { value, _ -> value },
        )
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === original).shouldBeTrue()
        original.suppressed.size shouldBeEqualTo 0
    }

    @Test
    fun `action과 cleanup의 동일 오류는 자기 suppression 없이 완료한다`() {
        val original = IllegalStateException("shared failure")
        val result = completeAfter(
            source = CompletableFuture.failedFuture<String>(original),
            cleanup = { throw original },
            transform = { value, _ -> value },
        )
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === original).shouldBeTrue()
    }

    @Test
    fun `비표준 scheduler 실패에서도 fallback 정리 후 결과를 완료한다`() {
        val cleanupCalls = AtomicInteger()
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            executor = Executor { throw IllegalStateException("scheduler failed") },
            cleanup = { cleanupCalls.incrementAndGet() },
            transform = { value, _ -> value },
        )

        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "done"
        cleanupCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `caller cancellation thread에서 blocking cleanup을 실행하지 않는다`() {
        val source = CompletableFuture<String>()
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupThread = AtomicReference<String>()
        val cleanupCalls = AtomicInteger()
        val result = completeAfter(
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
        }.apply { name = "cleanup-caller" }

        try {
            caller.start()
            cleanupStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            caller.join(1_000)
            caller.isAlive.shouldBeFalse()
            (cleanupThread.get() == "cleanup-caller").shouldBeFalse()
            result.isDone.shouldBeFalse()

            releaseCleanup.countDown()
            result.get(2, TimeUnit.SECONDS) shouldBeEqualTo null
            cleanupCalls.get() shouldBeEqualTo 1
        } finally {
            releaseCleanup.countDown()
            caller.join(1_000)
        }
    }

    @Test
    fun `정상 cleanup 이후 변환 결과를 반환한다`() {
        val cleaned = AtomicInteger()
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            cleanup = { cleaned.incrementAndGet() },
        ) { value, _ ->
            cleaned.get() shouldBeEqualTo 1
            value
        }
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "done"
    }

    @Test
    fun `action 성공 뒤 cleanup 오류는 결과 실패가 된다`() {
        val cleanupFailure = IllegalStateException("cleanup failed")
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            cleanup = { throw cleanupFailure },
        ) { value, _ -> value }
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === cleanupFailure).shouldBeTrue()
    }

    @Test
    fun `executor가 두 번 실행해도 cleanup은 한 번만 수행한다`() {
        val calls = AtomicInteger()
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            executor = Executor { task -> task.run(); task.run() },
            cleanup = { calls.incrementAndGet() },
        ) { value, _ -> value }
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "done"
        calls.get() shouldBeEqualTo 1
    }

    @Test
    fun `결과 취소가 이미 예약된 cleanup을 취소하지 않는다`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val result = completeAfter(
            source = CompletableFuture.completedFuture("done"),
            cleanup = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS).shouldBeTrue()
                cleaned.countDown()
            },
        ) { value, _ -> value }
        try {
            started.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            release.countDown()
            cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.isCancelled.shouldBeTrue()
        } finally {
            release.countDown()
        }
    }
}

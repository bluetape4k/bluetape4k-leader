package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.redisson.api.RKeys
import org.redisson.api.RLock
import org.redisson.api.RMap
import org.redisson.api.RPermitExpirableSemaphore
import org.redisson.api.RedissonClient
import org.redisson.misc.CompletableFutureWrapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RedissonAsyncCleanupTest {
    private val client = mockk<RedissonClient>()
    private val lock = mockk<RLock>()
    private val keys = mockk<RKeys>()
    private val owner = Thread.currentThread().threadId()
    private val ownership = CompletableFuture<Boolean>()
    private val expiry = CompletableFuture<Long>()
    private val unlocked = CompletableFuture<Void>()

    init {
        every { client.getLock("cleanup") } returns lock
        every { client.keys } returns keys
        every { lock.name } returns "cleanup"
        every { lock.tryLockAsync(any<Long>(), any<Long>(), TimeUnit.MILLISECONDS, owner) } returns
                CompletableFutureWrapper(CompletableFuture.completedFuture(true))
        every { lock.isHeldByThreadAsync(owner) } returns CompletableFutureWrapper(ownership)
        every { lock.unlockAsync(owner) } returns CompletableFutureWrapper(unlocked)
        every { keys.expireAsync(any<java.time.Duration>(), "cleanup") } returns CompletableFutureWrapper(expiry)
        every { lock.isHeldByThread(any()) } throws AssertionError("동기 ownership 호출 금지")
        every { keys.expire(any<java.time.Duration>(), "cleanup") } throws AssertionError("동기 expiry 호출 금지")
    }

    @Test
    fun `ownership과 expiry가 끝나기 전에는 결과를 완료하지 않는다`() {
        val result = run()
        result.isDone.shouldBeFalse()
        ownership.complete(true)
        result.isDone.shouldBeFalse()
        verify(exactly = 1) { keys.expireAsync(match { !it.isNegative && it <= java.time.Duration.ofSeconds(30) }, "cleanup") }
        expiry.complete(1)
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
        verify(exactly = 0) { lock.isHeldByThread(any()); keys.expire(any<java.time.Duration>(), "cleanup") }
    }

    @Test
    fun `ownership이 없으면 만료나 unlock을 요청하지 않는다`() {
        val result = run()
        ownership.complete(false)
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
        verify(exactly = 0) { keys.expireAsync(any<java.time.Duration>(), "cleanup"); lock.unlockAsync(any()) }
    }

    @Test
    fun `최소 lease가 없으면 획득 threadId로 비동기 해제한다`() {
        val result = run(minLease = Duration.ZERO)
        ownership.complete(true)
        result.isDone.shouldBeFalse()
        verify(exactly = 1) { lock.unlockAsync(owner) }
        unlocked.complete(null)
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
    }

    @Test
    fun `정리 future 실패는 성공한 action 결과를 바꾸지 않는다`() {
        val result = run()
        ownership.complete(true)
        expiry.completeExceptionally(IllegalStateException("cleanup"))
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
    }

    @Test
    fun `ownership 조회 실패도 원래 action 오류를 보존한다`() {
        val original = IllegalArgumentException("action")
        val result = run(action = CompletableFuture.failedFuture(original))
        ownership.completeExceptionally(IllegalStateException("ownership"))
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === original).shouldBeTrue()
        original.suppressed.size shouldBeEqualTo 0
    }

    @Test
    fun `executor 거부 후 최소 lease 정리도 native expiry를 기다린다`() {
        val rejected = RejectedExecutionException("rejected")
        val result = run(executor = Executor { throw rejected })
        result.isDone.shouldBeFalse()
        verify(exactly = 1) { keys.expireAsync(any<java.time.Duration>(), "cleanup") }
        expiry.completeExceptionally(IllegalStateException("cleanup"))
        val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
        (failure.cause === rejected).shouldBeTrue()
    }

    private fun run(
        minLease: Duration = 30.seconds,
        executor: Executor = Executor { it.run() },
        action: CompletableFuture<String> = CompletableFuture.completedFuture("ok"),
    ): CompletableFuture<String?> = RedissonLeaderElector(
        client, LeaderElectionOptions(leaseTime = 60.seconds, minLeaseTime = minLease),
    ).runAsyncIfLeader("cleanup", executor) { action }

    @Test
    fun `native expiry 요청 자체의 예외도 성공 결과를 바꾸지 않는다`() {
        every { keys.expireAsync(any<java.time.Duration>(), "cleanup") } throws IllegalStateException("request")
        val result = run()
        ownership.complete(true)
        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
    }

    @Test
    fun `action 취소는 정리 실패보다 우선한다`() {
        val action = CompletableFuture<String>()
        val result = run(action = action)
        action.cancel(false)
        ownership.complete(true)
        expiry.completeExceptionally(IllegalStateException("cleanup"))
        val failure = assertFailsWith<Exception> { result.get(2, TimeUnit.SECONDS) }
        (failure is CancellationException || failure.cause is CancellationException).shouldBeTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `group 정리 future 실패도 기존 action 결과 우선 정책을 유지한다`(actionFailed: Boolean) {
        val semaphore = mockk<RPermitExpirableSemaphore>()
        val audit = mockk<RMap<String, String>>()
        val cleanup = CompletableFuture<Boolean>()
        every { client.getPermitExpirableSemaphore("lg:{cleanup}") } returns semaphore
        every { client.getMap<String, String>("lg:{cleanup}:audit") } returns audit
        every { semaphore.trySetPermits(1) } returns true
        every { semaphore.tryAcquireAsync(any<Long>(), any<Long>(), TimeUnit.MILLISECONDS) } returns
                CompletableFutureWrapper(CompletableFuture.completedFuture("permit"))
        every { semaphore.updateLeaseTimeAsync("permit", any(), TimeUnit.MILLISECONDS) } returns
                CompletableFutureWrapper(cleanup)
        val original = IllegalArgumentException("action")
        val result = RedissonLeaderGroupElector(
            client, LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 60.seconds, minLeaseTime = 30.seconds),
        ).runAsyncIfLeader("cleanup", Executor { it.run() }) {
            if (actionFailed) CompletableFuture.failedFuture(original) else CompletableFuture.completedFuture("ok")
        }
        result.isDone.shouldBeFalse()
        cleanup.completeExceptionally(IllegalStateException("cleanup"))
        if (actionFailed) {
            val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            (failure.cause === original).shouldBeTrue()
            original.suppressed.size shouldBeEqualTo 0
        } else {
            result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "ok"
        }
    }
}

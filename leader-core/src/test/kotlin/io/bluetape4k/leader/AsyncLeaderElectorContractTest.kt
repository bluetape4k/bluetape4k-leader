package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.local.LocalAsyncLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.logging.KLogging
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * [AsyncLeaderElector] 인터페이스 계약을 검증하는 테스트입니다.
 *
 * [LocalAsyncLeaderElector]과 [LocalLeaderElector] 구현체를 통해
 * [AsyncLeaderElector.runAsyncIfLeader] 계약을 검증합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncLeaderElectorContractTest {

    companion object: KLogging()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── LocalAsyncLeaderElector 을 통한 AsyncLeaderElector 계약 검증 ──

    @Test
    fun `runAsyncIfLeader - 리더 획득 성공 시 CompletableFuture action 을 실행한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val result = election.runAsyncIfLeader(randomLockName()) {
            completableFutureOf("contract-ok")
        }.join()
        result shouldBeEqualTo "contract-ok"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val f1 = election.runAsyncIfLeader(randomLockName()) { completableFutureOf(1) }
        val f2 = election.runAsyncIfLeader(randomLockName()) { completableFutureOf(2) }

        f1.join() shouldBeEqualTo 1
        f2.join() shouldBeEqualTo 2
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 시 CompletionException 이 전파된다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                failedCompletableFutureOf<String>(RuntimeException("계약 위반 예외"))
            }.join()
        }
    }

    @Test
    fun `runAsyncIfLeader - action 실패 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val lockName = randomLockName()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(lockName) {
                failedCompletableFutureOf<Unit>(RuntimeException("실패"))
            }.join()
        }.cause.shouldBeInstanceOf<RuntimeException>()

        val result = election.runAsyncIfLeader(lockName) {
            completableFutureOf("복구")
        }.join().shouldNotBeNull()

        result shouldBeEqualTo "복구"
    }

    @Test
    fun `runAsyncIfLeaderResult - action future 실패는 ActionFailed 로 분류한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val slot = LeaderSlot(randomLockName(), "async-node")
        val failure = IllegalStateException("async-boom")

        val result = election.runAsyncIfLeaderResult(slot) {
            failedCompletableFutureOf<Any?>(failure)
        }.join().shouldNotBeNull()

        result.shouldBeInstanceOf<LeaderRunResult.ActionFailed>()
        result.cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않는다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val slot = LeaderSlot(randomLockName(), "async-node")
        val cancellation = CancellationException("async-cancelled")

        val thrown = assertFailsWith<CompletionException> {
            election.runAsyncIfLeaderResult(slot) {
                failedCompletableFutureOf<Any?>(cancellation)
            }.join()
        }

        thrown.cause.shouldBeInstanceOf<CancellationException>()
    }

    @Test
    fun `runAsyncIfLeader - 커스텀 executor 를 사용할 수 있다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = election.runAsyncIfLeader(randomLockName(), executor) {
                completableFutureOf("custom-executor-ok")
            }.join().shouldNotBeNull()

            result shouldBeEqualTo "custom-executor-ok"
        } finally {
            executor.shutdown()
        }
    }

    // ── LocalLeaderElector 을 통한 AsyncLeaderElector 계약 검증 ──

    @Test
    fun `runAsyncIfLeader - LocalLeaderElector 도 AsyncLeaderElector 계약을 준수한다`() {
        val election: AsyncLeaderElector = LocalLeaderElector()

        val result = election.runAsyncIfLeader(randomLockName()) {
            completableFutureOf(99)
        }.join()

        result shouldBeEqualTo 99
    }

    @Test
    fun `runAsyncIfLeaderResult - LocalLeaderElector default bridge 도 ActionFailed 를 반환한다`() {
        val election: AsyncLeaderElector = LocalLeaderElector()
        val slot = LeaderSlot(randomLockName(), "bridge-node")
        val failure = IllegalArgumentException("bridge-boom")

        val result = election.runAsyncIfLeaderResult(slot) {
            failedCompletableFutureOf<Any?>(failure)
        }.join().shouldNotBeNull()

        result.shouldBeInstanceOf<LeaderRunResult.ActionFailed>()
        result.cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeaderResult - 반환 future 취소가 원본 future 로 전파된다`() {
        val source = CompletableFuture<Any?>()
        val election = object: AsyncLeaderElector {
            @Suppress("UNCHECKED_CAST")
            override fun <T> runAsyncIfLeader(
                lockName: String,
                executor: java.util.concurrent.Executor,
                action: () -> CompletableFuture<T>,
            ): CompletableFuture<T?> = source as CompletableFuture<T?>
        }
        val slot = LeaderSlot(randomLockName(), "bridge-cancel-node")

        val result = election.runAsyncIfLeaderResult(slot) {
            completableFutureOf("unused")
        }

        result.cancel(false).shouldBeTrue()
        source.isCancelled.shouldBeTrue()
    }

    @Test
    fun `runAsyncIfLeaderResult - 반환 future 취소가 action future와 lease lifecycle로 전파된다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val lockName = randomLockName()
        val slot = LeaderSlot(lockName, "async-cancel-node")
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = election.runAsyncIfLeaderResult(slot, executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(2.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()

            await atMost 2.seconds until {
                actionFuture.isCancelled
            }
            await atMost 2.seconds until {
                election.runAsyncIfLeader(lockName, executor) {
                    completableFutureOf("reacquired")
                }.get(1.seconds) == "reacquired"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeader - nullable 반환 future 취소가 모든 Local action과 lease lifecycle로 전파된다`() {
        listOf<AsyncLeaderElector>(LocalAsyncLeaderElector(), LocalLeaderElector())
            .forEach { election ->
                assertNullableCancellationPropagates(election, useSlot = false)
                assertNullableCancellationPropagates(election, useSlot = true)
            }
    }

    @Test
    fun `runAsyncIfLeader - nullable 반환 future를 acquisition 전에 취소하면 action을 시작하지 않는다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val executor = Executors.newSingleThreadExecutor()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val actionInvoked = AtomicBoolean()

        try {
            executor.submit {
                blockerStarted.countDown()
                releaseBlocker.await(2.seconds)
            }
            blockerStarted.await(2.seconds).shouldBeTrue()

            val result = election.runAsyncIfLeader(randomLockName(), executor) {
                actionInvoked.set(true)
                completableFutureOf("unexpected")
            }

            result.cancel(false).shouldBeTrue()
            releaseBlocker.countDown()
            executor.submit {}.get(2.seconds)
            actionInvoked.get().shouldBeFalse()
        } finally {
            releaseBlocker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeader - nullable Local overload는 executor rejection을 즉시 전파한다`() {
        val slot = LeaderSlot(randomLockName(), "rejected-node")
        val rejected = Executor { throw RejectedExecutionException("rejected") }
        val actionInvoked = AtomicBoolean()

        listOf<AsyncLeaderElector>(LocalAsyncLeaderElector(), LocalLeaderElector())
            .forEach { election ->
                assertFailsWith<RejectedExecutionException> {
                    election.runAsyncIfLeader(randomLockName(), rejected) {
                        actionInvoked.set(true)
                        completableFutureOf("unexpected")
                    }.join()
                }
                assertFailsWith<RejectedExecutionException> {
                    election.runAsyncIfLeader(slot, rejected) {
                        actionInvoked.set(true)
                        completableFutureOf("unexpected")
                    }.join()
                }
            }
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `runAsyncIfLeaderResult - listener decorator도 반환 future 취소를 action future로 전파한다`() {
        val election = LocalLeaderElector().withListeners()
        val lockName = randomLockName()
        val slot = LeaderSlot(lockName, "listener-cancel-node")
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = election.runAsyncIfLeaderResult(slot, executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(2.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()

            await atMost 2.seconds until {
                actionFuture.isCancelled
            }
            await atMost 2.seconds until {
                election.runAsyncIfLeader(lockName, executor) {
                    completableFutureOf("reacquired")
                }.get(1.seconds) == "reacquired"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `VirtualThreadLeaderElector result bridge 도 반환 future 취소를 원본으로 전파한다`() {
        val source = CompletableFuture<Any?>()
        val election = object: VirtualThreadLeaderElector {
            @Suppress("UNCHECKED_CAST")
            override fun <T> runAsyncIfLeader(
                lockName: String,
                action: () -> T,
            ): VirtualFuture<T?> = VirtualFuture(source as Future<T?>)
        }

        val slot = LeaderSlot(randomLockName(), "virtual-bridge-cancel-node")
        val result = election.runAsyncIfLeaderResult(slot) { "unused" }

        result.cancel(false).shouldBeTrue()
        source.isCancelled.shouldBeTrue()
    }

    @Test
    fun `runAsyncIfLeader - action 실패 시 CompletionException 을 전파한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                failedCompletableFutureOf<Int>(IllegalArgumentException("invalid"))
            }.join()
        }
    }

    private fun assertNullableCancellationPropagates(
        election: AsyncLeaderElector,
        useSlot: Boolean,
    ) {
        val lockName = randomLockName()
        val slot = LeaderSlot(lockName, "nullable-cancel-node")
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = if (useSlot) {
                election.runAsyncIfLeader(slot, executor) {
                    actionStarted.countDown()
                    actionFuture
                }
            } else {
                election.runAsyncIfLeader(lockName, executor) {
                    actionStarted.countDown()
                    actionFuture
                }
            }

            actionStarted.await(2.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            await atMost 2.seconds until {
                actionFuture.isCancelled
            }
            await atMost 2.seconds until {
                election.runAsyncIfLeader(lockName, executor) {
                    completableFutureOf("reacquired")
                }.get(1.seconds) == "reacquired"
            }
        } finally {
            actionFuture.cancel(true)
            executor.shutdownNow()
        }
    }
}

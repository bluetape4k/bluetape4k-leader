package io.bluetape4k.leader

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.local.LocalAsyncLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

/**
 * [AsyncLeaderElector] 인터페이스 계약을 검증하는 테스트입니다.
 *
 * [LocalAsyncLeaderElector]과 [LocalLeaderElector] 구현체를 통해
 * [AsyncLeaderElector.runAsyncIfLeader] 계약을 검증합니다.
 */
class AsyncLeaderElectorContractTest {

    companion object: KLogging()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── LocalAsyncLeaderElector 을 통한 AsyncLeaderElector 계약 검증 ──

    @Test
    fun `runAsyncIfLeader - 리더 획득 성공 시 CompletableFuture action 을 실행한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val result = election.runAsyncIfLeader(randomLockName()) {
            CompletableFuture.completedFuture("contract-ok")
        }.join()
        result shouldBeEqualTo "contract-ok"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val f1 = election.runAsyncIfLeader(randomLockName()) { CompletableFuture.completedFuture(1) }
        val f2 = election.runAsyncIfLeader(randomLockName()) { CompletableFuture.completedFuture(2) }

        f1.join() shouldBeEqualTo 1
        f2.join() shouldBeEqualTo 2
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 시 CompletionException 이 전파된다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                CompletableFuture.failedFuture<String>(RuntimeException("계약 위반 예외"))
            }.join()
        }
    }

    @Test
    fun `runAsyncIfLeader - action 실패 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.failedFuture<Unit>(RuntimeException("실패"))
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture("복구")
        }.join()
        result shouldBeEqualTo "복구"
    }

    @Test
    fun `runAsyncIfLeaderResult - action future 실패는 ActionFailed 로 분류한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val failure = IllegalStateException("async-boom")

        val result = election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "async-node")) {
            CompletableFuture.failedFuture<Any?>(failure)
        }.join()

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않는다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val cancellation = CancellationException("async-cancelled")

        val thrown = assertFailsWith<CompletionException> {
            election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "async-node")) {
                CompletableFuture.failedFuture<Any?>(cancellation)
            }.join()
        }

        thrown.cause shouldBeInstanceOf CancellationException::class
    }

    @Test
    fun `runAsyncIfLeader - 커스텀 executor 를 사용할 수 있다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = election.runAsyncIfLeader(randomLockName(), executor) {
                CompletableFuture.completedFuture("custom-executor-ok")
            }.join()
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
            CompletableFuture.completedFuture(99)
        }.join()
        result shouldBeEqualTo 99
    }

    @Test
    fun `runAsyncIfLeaderResult - LocalLeaderElector default bridge 도 ActionFailed 를 반환한다`() {
        val election: AsyncLeaderElector = LocalLeaderElector()
        val failure = IllegalArgumentException("bridge-boom")

        val result = election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "bridge-node")) {
            CompletableFuture.failedFuture<Any?>(failure)
        }.join()

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeader - isFailure 가 action 실패를 정확히 반영한다`() {
        val election: AsyncLeaderElector = LocalAsyncLeaderElector()
        val outcome = runCatching {
            election.runAsyncIfLeader(randomLockName()) {
                CompletableFuture.failedFuture<Int>(IllegalArgumentException("invalid"))
            }.join()
        }
        outcome.isFailure.shouldBeTrue()
    }
}

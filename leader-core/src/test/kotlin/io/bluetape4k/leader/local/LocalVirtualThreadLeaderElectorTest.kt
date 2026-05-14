package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class LocalVirtualThreadLeaderElectorTest {

    companion object: KLogging()

    private val election = LocalVirtualThreadLeaderElector()

    private fun randomLockName() = "vt-lock-${Base58.randomString(9)}"

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomLockName()) { "hello" }.await()
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() {
        val f1 = election.runAsyncIfLeader(randomLockName()) { "a" }
        val f2 = election.runAsyncIfLeader(randomLockName()) { "b" }

        f1.await() shouldBeEqualTo "a"
        f2.await() shouldBeEqualTo "b"
    }

    @Test
    fun `runAsyncIfLeader - action 예외 발생 시 await 호출 시 예외가 전파된다`() {
        val result = runCatching {
            election.runAsyncIfLeader(randomLockName()) {
                throw LeaderElectionException("테스트 예외")
            }.await()
        }
        result.isFailure.shouldBeTrue()
    }

    @Test
    fun `runAsyncIfLeader - action 예외 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                throw LeaderElectionException("실패")
            }.await()
        }

        // 락이 해제된 상태여야 다음 호출이 정상 실행된다
        val result = election.runAsyncIfLeader(lockName) { "복구 성공" }.await()
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runAsyncIfLeader - toCompletableFuture 로 변환하여 결과를 소비할 수 있다`() {
        val result = election
            .runAsyncIfLeader(randomLockName()) { 42 }
            .toCompletableFuture()
            .join()

        result shouldBeEqualTo 42
    }

    @Test
    fun `runAsyncIfLeaderResult - action 실패는 ActionFailed 로 분류한다`() {
        val failure = IllegalStateException("virtual-boom")

        val result = election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "virtual-node")) {
            throw failure
        }.await()

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않는다`() {
        val cancellation = CancellationException("virtual-cancelled")

        val thrown = assertFailsWith<CompletionException> {
            election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "virtual-node")) {
                throw cancellation
            }.toCompletableFuture().join()
        }

        thrown.cause shouldBeInstanceOf CancellationException::class
    }

    @Test
    fun `runAsyncIfLeader - 멀티스레드 동시 실행 시 직렬 처리를 보장한다`() {
        val lockName = randomLockName()
        val counter = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runAsyncIfLeader(lockName) {
                    log.debug { "Virtual Thread 작업 1 실행. counter=${counter.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }.await()
            }
            .add {
                election.runAsyncIfLeader(lockName) {
                    log.debug { "Virtual Thread 작업 2 실행. counter=${counter.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }.await()
            }
            .run()

        counter.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    // ── skip-behavior (ShedLock 방식): 락 획득 실패 시 null 반환 ──────────

    @Test
    fun `runAsyncIfLeader - waitTime 내 락 획득 실패 시 null 을 반환한다`() {
        val skipElection = LocalVirtualThreadLeaderElector(
            LeaderElectionOptions(waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()
        val latch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        // 동일 election 인스턴스로 락 점유
        val firstThread = Thread {
            skipElection.runAsyncIfLeader(lockName) {
                latch.countDown()
                releaseLatch.await()
            }.await()
        }.apply { start() }

        latch.await(2, TimeUnit.SECONDS)

        val result = skipElection.runAsyncIfLeader(lockName) { "should-skip" }.await()
        result.shouldBeNull()

        releaseLatch.countDown()
        firstThread.join()
    }

    @Test
    fun `runAsyncIfLeader - 락 해제 후 재시도 시 정상 실행된다`() {
        val shortWaitElection = LocalVirtualThreadLeaderElector(
            LeaderElectionOptions(waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()

        val first = shortWaitElection.runAsyncIfLeader(lockName) { "first" }.await()
        first shouldBeEqualTo "first"

        val second = shortWaitElection.runAsyncIfLeader(lockName) { "second" }.await()
        second shouldBeEqualTo "second"
    }
}

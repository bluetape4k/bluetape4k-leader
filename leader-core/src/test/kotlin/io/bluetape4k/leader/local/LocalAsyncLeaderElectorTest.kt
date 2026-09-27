package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LocalAsyncLeaderElectorTest {

    companion object: KLogging()

    private val election = LocalAsyncLeaderElector()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomLockName()) {
            completableFutureOf("async-ok")
        }.join()

        result shouldBeEqualTo "async-ok"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() {
        val future1 = election.runAsyncIfLeader(randomLockName()) {
            completableFutureOf("a")
        }
        val future2 = election.runAsyncIfLeader(randomLockName()) {
            completableFutureOf("b")
        }

        future1.join() shouldBeEqualTo "a"
        future2.join() shouldBeEqualTo "b"
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 시 CompletionException 이 전파된다`() {
        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                failedCompletableFutureOf<String>(IllegalStateException("비동기 실패"))
            }.join()
        }
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                failedCompletableFutureOf<Int>(RuntimeException("실패"))
            }.join()
        }

        // 락이 해제된 상태여야 다음 호출이 정상 실행된다
        val result = election.runAsyncIfLeader(lockName) {
            completableFutureOf(99)
        }.join()

        result shouldBeEqualTo 99
    }

    @Test
    fun `runAsyncIfLeader - action 내부 예외 발생 시 CompletionException 이 전파된다`() {
        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                CompletableFuture.supplyAsync<Int> {
                    throw LeaderElectionException("action 내부 예외")
                }
            }.join()
        }
    }

    @Test
    fun `runAsyncIfLeader - 멀티스레드 동시 실행 시 직렬 처리를 보장한다`() {
        val lockName = randomLockName()
        val counter = AtomicInteger(0)

        val numThreads = 2 * Runtimex.availableProcessors
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runAsyncIfLeader(lockName) {
                    CompletableFuture.supplyAsync {
                        log.debug { "비동기 작업 1 실행. counter=${counter.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        counter.incrementAndGet()
                    }
                }.join()
            }
            .add {
                election.runAsyncIfLeader(lockName) {
                    CompletableFuture.supplyAsync {
                        log.debug { "비동기 작업 2 실행. counter=${counter.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        counter.incrementAndGet()
                    }
                }.join()
            }
            .run()

        counter.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    // ── skip-behavior (ShedLock 방식): 락 획득 실패 시 null 반환 ──────────

    @Test
    fun `runAsyncIfLeader - waitTime 내 락 획득 실패 시 null 을 반환한다`() {
        val skipElection = LocalAsyncLeaderElector(
            LeaderElectionOptions(waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()
        val latch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        // 동일 election 인스턴스로 락 점유
        val firstThread = thread {
            skipElection.runAsyncIfLeader(lockName) {
                CompletableFuture.runAsync {
                    latch.countDown()
                    releaseLatch.await()
                }
            }.join()
        }

        latch.await(2.seconds).shouldBeTrue()

        val result = skipElection.runAsyncIfLeader(lockName) {
            completableFutureOf("should-skip")
        }.join()
        result.shouldBeNull()

        releaseLatch.countDown()
        firstThread.join()
    }

    @Test
    fun `runAsyncIfLeader - 락 해제 후 재시도 시 정상 실행된다`() {
        val shortWaitElection = LocalAsyncLeaderElector(
            LeaderElectionOptions(waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()

        val first = shortWaitElection.runAsyncIfLeader(lockName) {
            completableFutureOf("first")
        }.join()
        first shouldBeEqualTo "first"

        val second = shortWaitElection.runAsyncIfLeader(lockName) {
            completableFutureOf("second")
        }.join()
        second shouldBeEqualTo "second"
    }
}

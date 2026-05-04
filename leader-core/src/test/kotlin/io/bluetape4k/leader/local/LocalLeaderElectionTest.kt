package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class LocalLeaderElectionTest {

    companion object: KLogging()

    private val election = LocalLeaderElection()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runIfLeader(randomLockName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() {
        val result1 = election.runIfLeader(randomLockName()) { "a" }
        val result2 = election.runIfLeader(randomLockName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 호출자에게 전파된다`() {
        assertThrows<LeaderElectionException> {
            election.runIfLeader(randomLockName()) {
                throw LeaderElectionException("테스트 예외")
            }
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching {
            election.runIfLeader(lockName) { throw LeaderElectionException("실패") }
        }

        // 락이 해제된 상태여야 다음 호출이 정상 실행된다
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 동일 스레드에서 동일 lockName 으로 중첩 호출(재진입)이 가능하다`() {
        val lockName = randomLockName()
        val result = election.runIfLeader(lockName) {
            // ReentrantLock 은 동일 스레드에서 재진입이 가능하다
            election.runIfLeader(lockName) { "재진입 성공" }
        }
        result shouldBeEqualTo "재진입 성공"
    }

    @Test
    fun `runIfLeader - 멀티스레드 동시 실행 시 직렬 처리를 보장한다`() {
        val lockName = randomLockName()
        val counter = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 1 실행. counter=${counter.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 2 실행. counter=${counter.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }
            }
            .run()

        // workers * rounds = 8 * 4 = 32 태스크, block 이 2개이므로 각 block 은 16번 실행
        counter.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomLockName()) {
            CompletableFuture.completedFuture("async-ok")
        }.join()

        result shouldBeEqualTo "async-ok"
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 시 CompletionException 이 전파된다`() {
        assertThrows<CompletionException> {
            election.runAsyncIfLeader(randomLockName()) {
                CompletableFuture.failedFuture<String>(IllegalStateException("비동기 실패"))
            }.join()
        }
    }

    @Test
    fun `runAsyncIfLeader - action future 실패 후에도 락이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.failedFuture<Int>(RuntimeException("실패"))
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture(42)
        }.join()

        result shouldBeEqualTo 42
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
    fun `runIfLeader - waitTime 내 락 획득 실패 시 null 을 반환한다`() {
        // 짧은 waitTime 으로 설정한 단일 election 인스턴스 사용
        val skipElection = LocalLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(100))
        )
        val lockName = randomLockName()
        val latch = java.util.concurrent.CountDownLatch(1)
        val releaseLatch = java.util.concurrent.CountDownLatch(1)

        // 첫 번째 스레드: 동일 election 으로 락을 획득하고 오래 대기
        val firstThread = Thread {
            skipElection.runIfLeader(lockName) {
                latch.countDown()
                releaseLatch.await() // 메인 스레드가 skip 확인 후 해제
            }
        }.apply { start() }

        latch.await() // 첫 번째 스레드가 락을 획득할 때까지 대기

        // 두 번째 시도: 동일 election, 짧은 waitTime(100ms) 으로 → null 반환
        val result = skipElection.runIfLeader(lockName) { "should-skip" }
        result.shouldBeNull()

        releaseLatch.countDown()
        firstThread.join()
    }

    @Test
    fun `runIfLeader - 락이 해제되면 이후 호출이 정상 실행된다`() {
        val shortWaitElection = LocalLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(100))
        )
        val lockName = randomLockName()

        // 첫 번째 실행: 정상적으로 락 획득 및 해제
        val first = shortWaitElection.runIfLeader(lockName) { "first" }
        first shouldBeEqualTo "first"

        // 두 번째 실행: 락이 해제된 후이므로 정상 실행
        val second = shortWaitElection.runIfLeader(lockName) { "second" }
        second shouldBeEqualTo "second"
    }

    @Test
    fun `runAsyncIfLeader - waitTime 내 락 획득 실패 시 null 을 반환한다`() {
        val skipElection = LocalLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(100))
        )
        val lockName = randomLockName()
        val latch = java.util.concurrent.CountDownLatch(1)
        val releaseLatch = java.util.concurrent.CountDownLatch(1)

        val firstThread = Thread {
            skipElection.runIfLeader(lockName) {
                latch.countDown()
                releaseLatch.await()
            }
        }.apply { start() }

        latch.await()

        val result = skipElection.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture("should-skip")
        }.join()
        result.shouldBeNull()

        releaseLatch.countDown()
        firstThread.join()
    }
}

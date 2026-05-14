package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class LocalVirtualThreadLeaderGroupElectorTest {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders)
    private val election = LocalVirtualThreadLeaderGroupElector(options)

    private fun randomLockName() = "vt-lock-${Base58.randomString(8)}"

    // ── 기본 동작 ──────────────────────────────────────────────────────────

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomLockName()) { "hello" }.await()
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적인 슬롯 풀을 가진다`() {
        val f1 = election.runAsyncIfLeader(randomLockName()) { "a" }
        val f2 = election.runAsyncIfLeader(randomLockName()) { "b" }

        f1.await() shouldBeEqualTo "a"
        f2.await() shouldBeEqualTo "b"
    }

    @Test
    fun `runAsyncIfLeader - action 예외 발생 시 await 호출 시 예외가 전파된다`() {
        val result = runCatching {
            election.runAsyncIfLeader(randomLockName()) {
                throw LeaderGroupElectionException("테스트 예외")
            }.await()
        }
        result.isFailure.shouldBeTrue()
    }

    @Test
    fun `runAsyncIfLeader - action 예외 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                throw LeaderGroupElectionException("실패")
            }.await()
        }

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

    // ── 동시 실행 제한 ────────────────────────────────────────────────────

    @Test
    fun `runAsyncIfLeader - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomLockName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(maxLeaders * 4)
            .rounds(2)
            .add {
                election.runAsyncIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(Random.nextLong(5, 15))
                    currentConcurrent.decrementAndGet()
                }.await()
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=$maxLeaders" }
        peakConcurrent.get() shouldBeLessOrEqualTo maxLeaders
    }

    @Test
    fun `maxLeaders 개 슬롯이 모두 사용 중일 때 추가 요청은 블로킹된다`() {
        val lockName = randomLockName()
        val startLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders + 1)

        // maxLeaders 개 슬롯을 모두 점유
        repeat(maxLeaders) {
            executor.submit {
                election.runAsyncIfLeader(lockName) {
                    startLatch.countDown()
                    holdLatch.await()
                }.await()
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        // 슬롯이 가득 찬 상태 검증
        election.state(lockName).isFull.shouldBeTrue()
        election.activeCount(lockName) shouldBeEqualTo maxLeaders
        election.availableSlots(lockName) shouldBeEqualTo 0

        // 추가 요청은 블로킹되어야 함
        val extraStarted = AtomicInteger(0)
        executor.submit {
            election.runAsyncIfLeader(lockName) { extraStarted.incrementAndGet() }.await()
        }
        Thread.sleep(50)
        extraStarted.get() shouldBeEqualTo 0

        // 슬롯 해제 → 추가 요청이 실행됨
        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        extraStarted.get() shouldBeEqualTo 1
    }

    // ── 상태 정보 ────────────────────────────────────────────────────────

    @Test
    fun `state - 초기 상태는 activeCount=0, isFull=false, isEmpty=true 이다`() {
        val lockName = randomLockName()
        val state = election.state(lockName)

        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.availableSlots shouldBeEqualTo maxLeaders
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
    }

    @Test
    fun `state - maxLeaders 가 모두 활성이면 isFull=true`() {
        val lockName = randomLockName()
        val startLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders)

        repeat(maxLeaders) {
            executor.submit {
                election.runAsyncIfLeader(lockName) {
                    startLatch.countDown()
                    holdLatch.await()
                }.await()
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        election.state(lockName).isFull.shouldBeTrue()
        election.availableSlots(lockName) shouldBeEqualTo 0

        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    // ── 스트레스 테스트 ────────────────────────────────────────────────────

    @Test
    fun `멀티스레드 스트레스 - 모든 실행이 완료되고 카운터가 정확하다`() {
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

    @Test
    fun `runAsyncIfLeaderResult - action 실패는 ActionFailed 로 분류한다`() {
        val failure = IllegalArgumentException("virtual-group-boom")

        val result = election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "virtual-group-node")) {
            throw failure
        }.await()

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
    }

    @Test
    fun `runAsyncIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않는다`() {
        val cancellation = CancellationException("virtual-group-cancelled")

        val thrown = assertFailsWith<CompletionException> {
            election.runAsyncIfLeaderResult(LeaderSlot(randomLockName(), "virtual-group-node")) {
                throw cancellation
            }.toCompletableFuture().join()
        }

        thrown.cause shouldBeInstanceOf CancellationException::class
    }

    // ── skip-behavior (ShedLock 방식): 슬롯 획득 실패 시 null 반환 ──────────

    @Test
    fun `runAsyncIfLeader - waitTime 내 슬롯 획득 실패 시 null 을 반환한다`() {
        val skipElection = LocalVirtualThreadLeaderGroupElector(
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()
        val latch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        // 동일 election 인스턴스로 슬롯 점유 (maxLeaders=1 이므로 가득 참)
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
    fun `runAsyncIfLeader - 슬롯 해제 후 재시도 시 정상 실행된다`() {
        val shortWaitElection = LocalVirtualThreadLeaderGroupElector(
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()

        val first = shortWaitElection.runAsyncIfLeader(lockName) { "first" }.await()
        first shouldBeEqualTo "first"

        val second = shortWaitElection.runAsyncIfLeader(lockName) { "second" }.await()
        second shouldBeEqualTo "second"
    }
}

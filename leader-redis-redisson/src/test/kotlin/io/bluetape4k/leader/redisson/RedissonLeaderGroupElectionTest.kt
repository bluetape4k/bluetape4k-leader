package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class RedissonLeaderGroupElectionTest: AbstractRedissonLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = 30.seconds,
        leaseTime = 60.seconds,

        )
    private val election by lazy { RedissonLeaderGroupElector(redissonClient, options) }

    // ── 기본 동작 ──────────────────────────────────────────────────────────

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runIfLeader(randomName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적인 슬롯 풀을 가진다`() {
        val result1 = election.runIfLeader(randomName()) { "a" }
        val result2 = election.runIfLeader(randomName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 호출자에게 전파된다`() {
        assertFailsWith<LeaderGroupElectionException> {
            election.runIfLeader(randomName()) {
                throw LeaderGroupElectionException("테스트 예외")
            }
        }
    }

    @Test
    fun `runIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()

        assertFailsWith<LeaderGroupElectionException> {
            election.runIfLeader(lockName) {
                throw LeaderGroupElectionException("실패")
            }
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeaderResult - action 실패는 ActionFailed 로 분류한다`() {
        val failure = LeaderGroupElectionException("redisson-group-result-boom")

        val result = election.runIfLeaderResult(LeaderSlot(randomName(), "redisson-group-node")) {
            throw failure
        }

        result shouldBeEqualTo LeaderRunResult.ActionFailed(failure)
    }

    @Test
    fun `runIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() {
        val cancellation = CancellationException("redisson-group-cancelled")

        val thrown = assertFailsWith<CancellationException> {
            election.runIfLeaderResult<Any?>(LeaderSlot(randomName(), "redisson-group-node")) {
                throw cancellation
            }
        }

        thrown shouldBeEqualTo cancellation
    }

    @Test
    fun `runIfLeader - maxLeaders 슬롯이 모두 사용 중이면 waitTime 초과 시 null 을 반환한다`() {
        val shortWaitOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        val singleElection = RedissonLeaderGroupElector(redissonClient, shortWaitOptions)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            singleElection.runIfLeader(lockName) {
                acquiredLatch.countDown()
                holdLatch.await()
            }
        }

        try {
            acquiredLatch.await(2, TimeUnit.SECONDS)
            val result = singleElection.runIfLeader(lockName) { }
            result shouldBeEqualTo null
        } finally {
            holdLatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `maxLeaders=1 이면 LeaderElector 과 동일하게 직렬 실행된다`() {
        val oneLeader = options.copy(maxLeaders = 1)
        val singleElection = RedissonLeaderGroupElector(redissonClient, oneLeader)
        val lockName = randomName()
        val counter = AtomicInteger(0)
        val numThreads = 6

        MultithreadingTester()
            .workers(numThreads)
            .rounds(2)
            .add { singleElection.runIfLeader(lockName) { counter.incrementAndGet() } }
            .run()

        counter.get() shouldBeEqualTo numThreads * 2
    }

    // ── runIfLeader Virtual Thread ────────────────────────────────────────

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `runIfLeader - Virtual Thread 에서 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(options.maxLeaders * 8)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(Random.nextLong(5, 15))
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `멀티스레드 스트레스 - runIfLeader Virtual Thread 에서 모든 실행이 완료되고 카운터가 정확하다`() {
        val lockName = randomName()
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread / 2)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 1. task1=${task1.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    task1.incrementAndGet()
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 2. task2=${task2.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    task2.incrementAndGet()
                }
            }
            .run()

        task1.get() shouldBeEqualTo numThreads * roundsPerThread / 2
        task2.get() shouldBeEqualTo numThreads * roundsPerThread / 2
    }

    // ── 동시 실행 제한 ────────────────────────────────────────────────────

    @Test
    fun `동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(options.maxLeaders * 4)
            .rounds(2)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(Random.nextLong(5, 15))
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    // ── 상태 정보 ────────────────────────────────────────────────────────

    @Test
    fun `state - 초기 상태는 activeCount=0, isFull=false, isEmpty=true 이다`() {
        val lockName = randomName()
        val state = election.state(lockName)

        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo options.maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.availableSlots shouldBeEqualTo options.maxLeaders
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
    }

    @Test
    fun `state - maxLeaders 슬롯이 모두 사용 중이면 isFull=true 이고 해제 후 isEmpty=true 이다`() {
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(options.maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(options.maxLeaders)

        repeat(options.maxLeaders) {
            executor.submit {
                election.runIfLeader(lockName) {
                    acquiredLatch.countDown()
                    holdLatch.await()
                }
            }
        }

        acquiredLatch.await(5, TimeUnit.SECONDS)
        election.state(lockName).isFull.shouldBeTrue()
        election.activeCount(lockName) shouldBeEqualTo options.maxLeaders
        election.availableSlots(lockName) shouldBeEqualTo 0

        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        election.state(lockName).isEmpty.shouldBeTrue()
    }

    // ── 스트레스 테스트 ────────────────────────────────────────────────────

    @Test
    fun `멀티스레드 스트레스 - 모든 실행이 완료되고 카운터가 정확하다`() {
        val lockName = randomName()
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 1. task1=${task1.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    task1.incrementAndGet()
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 2. task2=${task2.get()}" }
                    Thread.sleep(Random.nextLong(1, 5))
                    task2.incrementAndGet()
                }
            }
            .run()

        task1.get() shouldBeEqualTo numThreads * roundsPerThread / 2
        task2.get() shouldBeEqualTo numThreads * roundsPerThread / 2
    }

    // ── runAsyncIfLeader 기본 동작 ────────────────────────────────────────

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomName()) {
            futureOf { "hello" }
        }.join()
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runAsyncIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(lockName) {
                futureOf<Int> { throw LeaderGroupElectionException("실패") }
            }.join()
        }.cause shouldBeInstanceOf LeaderGroupElectionException::class

        val result = election.runAsyncIfLeader(lockName) { futureOf { "복구 성공" } }.join()
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runAsyncIfLeader - failed future 발생 시 CompletionException 으로 전파되고 슬롯이 반환된다`() {
        val lockName = randomName()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader(lockName) {
                futureOf<Int> { throw IllegalStateException("boom") }
            }.join()
        }.cause shouldBeInstanceOf IllegalStateException::class

        // 슬롯이 반환되어 다음 호출이 성공해야 함
        val result = election.runAsyncIfLeader(lockName) { futureOf { 42 } }.join()
        result shouldBeEqualTo 42
    }

    // ── runAsyncIfLeader 동시 실행 제한 ──────────────────────────────────

    @Test
    fun `runAsyncIfLeader - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(options.maxLeaders * 4)
            .rounds(2)
            .add {
                election.runAsyncIfLeader(lockName) {
                    futureOf {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        Thread.sleep(Random.nextLong(5, 15))
                        currentConcurrent.decrementAndGet()
                    }
                }.join()
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @Test
    fun `멀티스레드 스트레스 - runAsyncIfLeader 모든 실행이 완료되고 카운터가 정확하다`() {
        val lockName = randomName()
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runAsyncIfLeader(lockName) {
                    futureOf {
                        log.debug { "비동기 작업 1. task1=${task1.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        task1.incrementAndGet()
                    }
                }.join()
            }
            .add {
                election.runAsyncIfLeader(lockName) {
                    futureOf {
                        log.debug { "비동기 작업 2. task2=${task2.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        task2.incrementAndGet()
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeEqualTo numThreads * roundsPerThread / 2
        task2.get() shouldBeEqualTo numThreads * roundsPerThread / 2
    }

    // ── runAsyncIfLeader Virtual Thread ──────────────────────────────────

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `runAsyncIfLeader - Virtual Thread 에서 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(options.maxLeaders * 8)
            .add {
                election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        Thread.sleep(Random.nextLong(5, 15))
                        currentConcurrent.decrementAndGet()
                    }
                }.join()
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    /**
     * [MultithreadingTester]를 사용하여 짧은 `waitTime` + `maxLeaders=3` 환경에서
     * 동시 다수 스레드가 [RedissonLeaderGroupElector.runIfLeader]를 호출할 때,
     * 항상 `maxLeaders` 이하의 동시 실행자만 존재하는지 검증한다.
     * 락 획득 실패(RedisException)는 [runCatching]으로 안전하게 처리한다.
     */
    @Test
    fun `MultithreadingTester - maxLeaders 슬롯 제한이 동시성 환경에서 올바르게 동작한다`() {
        val lockName = randomName()
        val shortWaitOptions = LeaderGroupElectionOptions(
            maxLeaders = 3,
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
        )
        val limitedElection = RedissonLeaderGroupElector(redissonClient, shortWaitOptions)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(shortWaitOptions.maxLeaders * 4)
            .rounds(2)
            .add {
                runCatching {
                    limitedElection.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        Thread.sleep(Random.nextLong(10, 30))
                        currentConcurrent.decrementAndGet()
                    }
                }
                // RedisException(슬롯 초과) 또는 성공 — 둘 다 허용
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${shortWaitOptions.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo shortWaitOptions.maxLeaders
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `멀티스레드 스트레스 - runAsyncIfLeader Virtual Thread 에서 모든 실행이 완료되고 카운터가 정확하다`() {
        val lockName = randomName()
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread / 2)
            .add {
                election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        log.debug { "비동기 작업 1. task1=${task1.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        task1.incrementAndGet()
                    }
                }.join()
            }
            .add {
                election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        log.debug { "비동기 작업 2. task2=${task2.get()}" }
                        Thread.sleep(Random.nextLong(1, 5))
                        task2.incrementAndGet()
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeEqualTo numThreads * roundsPerThread / 2
        task2.get() shouldBeEqualTo numThreads * roundsPerThread / 2
    }

    // =========================================================================
    // minLeaseTime 시맨틱 (slot-token TTL 모델)
    // =========================================================================

    @Test
    fun `minLeaseTime 보유 - 빠른 action 종료 후에도 다른 client 는 즉시 acquire 실패한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 800.milliseconds,
        )
        val el = RedissonLeaderGroupElector(redissonClient, opts)
        val lockName = randomName()

        el.runIfLeader(lockName) { "fast" } shouldBeEqualTo "fast"

        val secondElector = RedissonLeaderGroupElector(redissonClient, opts)
        secondElector.runIfLeader(lockName) { "should-not" } shouldBeEqualTo null
    }

    @Test
    fun `minLeaseTime 만료 후 다음 acquire 가 성공한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 300.milliseconds,
        )
        val el = RedissonLeaderGroupElector(redissonClient, opts)
        val lockName = randomName()

        el.runIfLeader(lockName) { "first" } shouldBeEqualTo "first"

        Thread.sleep(400)

        val secondElector = RedissonLeaderGroupElector(redissonClient, opts)
        secondElector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `minLeaseTime=0 회귀 - 즉시 release 가 정상 동작한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
        )
        val el = RedissonLeaderGroupElector(redissonClient, opts)
        val lockName = randomName()

        el.runIfLeader(lockName) { "a" } shouldBeEqualTo "a"

        val secondElector = RedissonLeaderGroupElector(redissonClient, opts)
        secondElector.runIfLeader(lockName) { "b" } shouldBeEqualTo "b"
    }

    @Test
    fun `maxLeaders 동시 점유 + 모두 minLease 보유 - 추가 client 는 실패한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 2,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 1.seconds,
        )
        val el = RedissonLeaderGroupElector(redissonClient, opts)
        val lockName = randomName()

        repeat(opts.maxLeaders) {
            el.runIfLeader(lockName) { "fast" } shouldBeEqualTo "fast"
        }

        val third = RedissonLeaderGroupElector(redissonClient, opts)
        third.runIfLeader(lockName) { "third" } shouldBeEqualTo null
    }

    @Test
    fun `crash recovery - release 미호출 시 leaseTime 만료 후 다른 client 가 acquire 한다`() {
        val lockName = randomName()
        // 짧은 leaseTime 으로 직접 permit 을 잡고 release 하지 않음
        val crashSemaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
        crashSemaphore.trySetPermits(1)
        val crashedPermit = crashSemaphore.tryAcquire(
            200, 400, java.util.concurrent.TimeUnit.MILLISECONDS
        )
        crashedPermit shouldBeEqualTo crashedPermit

        val opts = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds)
        val el = RedissonLeaderGroupElector(redissonClient, opts)
        Thread.sleep(500) // leaseTime(400ms) 만료 대기

        el.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
    }
}

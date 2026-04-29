package io.bluetape4k.leader.hazelcast

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class HazelcastLeaderGroupElectionTest: AbstractHazelcastLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = Duration.ofSeconds(10),
        leaseTime = Duration.ofSeconds(60),
    )
    private val election by lazy { HazelcastLeaderGroupElection(hazelcastClient, options) }

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
    fun `runIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        runCatching { election.runIfLeader(lockName) { throw RuntimeException("실패") } }
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 모든 슬롯이 사용 중이면 waitTime 초과 시 null 을 반환한다`() {
        val shortWaitOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = Duration.ofMillis(100))
        val singleElection = HazelcastLeaderGroupElection(hazelcastClient, shortWaitOptions)
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
            result.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `state - 초기 상태는 activeCount=0, isEmpty=true 이다`() {
        val lockName = randomName()
        val state = election.state(lockName)
        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo options.maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
    }

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

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `Virtual Thread 환경에서 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
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

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomName()) { futureOf { "hello" } }.join()
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runAsyncIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                futureOf<Int> { throw RuntimeException("실패") }
            }.join()
        }
        val result = election.runAsyncIfLeader(lockName) { futureOf { "복구 성공" } }.join()
        result shouldBeEqualTo "복구 성공"
    }

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
}

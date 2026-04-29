package io.bluetape4k.leader.hazelcast

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
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

class HazelcastSuspendLeaderGroupElectionTest: AbstractHazelcastLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = Duration.ofSeconds(10),
        leaseTime = Duration.ofSeconds(60),
    )
    private val election by lazy { HazelcastSuspendLeaderGroupElection(hazelcastClient, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runTest {
        val result = election.runIfLeader(randomName()) {
            delay(10)
            "hello"
        }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적인 슬롯 풀을 가진다`() = runTest {
        val result1 = election.runIfLeader(randomName()) { "a" }
        val result2 = election.runIfLeader(randomName()) { "b" }
        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() = runTest {
        val lockName = randomName()
        runCatching { election.runIfLeader(lockName) { throw RuntimeException("실패") } }
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 모든 슬롯이 사용 중이면 waitTime 초과 시 null 을 반환한다`() = runTest {
        val shortWaitOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = Duration.ofMillis(100))
        val singleElection = HazelcastSuspendLeaderGroupElection(hazelcastClient, shortWaitOptions)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            val blockingElection = HazelcastLeaderGroupElection(
                hazelcastClient,
                shortWaitOptions.copy(waitTime = Duration.ofSeconds(5))
            )
            blockingElection.runIfLeader(lockName) {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
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
    fun `runIfLeader - 멀티스레드 환경에서 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(options.maxLeaders * 4)
            .rounds(2)
            .add {
                runBlocking {
                    election.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        delay(Random.nextLong(5, 15))
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `runIfLeader - Virtual Thread 환경에서 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(options.maxLeaders * 8)
            .add {
                runBlocking {
                    election.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        delay(Random.nextLong(5, 15))
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }
}

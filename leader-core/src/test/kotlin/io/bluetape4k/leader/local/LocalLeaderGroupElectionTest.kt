package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class LocalLeaderGroupElectionTest {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders)
    private val election = LocalLeaderGroupElector(options)

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── 기본 동작 ──────────────────────────────────────────────────────────

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runIfLeader(randomLockName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적인 슬롯 풀을 가진다`() {
        val result1 = election.runIfLeader(randomLockName()) { "a" }
        val result2 = election.runIfLeader(randomLockName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 호출자에게 전파된다`() {
        assertFailsWith<LeaderGroupElectionException> {
            election.runIfLeader(randomLockName()) { throw LeaderGroupElectionException("테스트 예외") }
        }
    }

    @Test
    fun `runIfLeader - action 예외 발생 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        runCatching { election.runIfLeader(lockName) { throw LeaderGroupElectionException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `maxLeaders=1 이면 LeaderElector 과 동일하게 직렬 실행된다`() {
        val singleElection = LocalLeaderGroupElector(LeaderGroupElectionOptions(1))
        val lockName = randomLockName()
        val counter = AtomicInteger(0)
        val numThreads = 6

        MultithreadingTester()
            .workers(numThreads)
            .rounds(2)
            .add { singleElection.runIfLeader(lockName) { counter.incrementAndGet() } }
            .run()

        counter.get() shouldBeEqualTo numThreads * 2
    }

    // ── 동시 실행 제한 ────────────────────────────────────────────────────

    @Test
    fun `동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomLockName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(maxLeaders * 4)
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

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=$maxLeaders" }
        peakConcurrent.get() shouldBeLessOrEqualTo maxLeaders
    }

    @Test
    fun `maxLeaders 개 슬롯이 모두 사용 중일 때 추가 요청은 블로킹된다`() {
        val lockName = randomLockName()
        val startLatch = CountDownLatch(maxLeaders)  // maxLeaders 개 슬롯이 모두 사용 시작
        val holdLatch = CountDownLatch(1)            // 슬롯 해제 신호
        val executor = Executors.newFixedThreadPool(maxLeaders + 1)

        // maxLeaders 개 슬롯을 모두 점유
        repeat(maxLeaders) {
            executor.submit {
                election.runIfLeader(lockName) {
                    startLatch.countDown()
                    holdLatch.await()
                }
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        // 슬롯이 가득 찬 상태 검증
        election.state(lockName).isFull.shouldBeTrue()
        election.activeCount(lockName) shouldBeEqualTo maxLeaders
        election.availableSlots(lockName) shouldBeEqualTo 0

        // 추가 요청은 블로킹되어야 함 (즉시 실행되면 안됨)
        val extraStarted = AtomicInteger(0)
        executor.submit {
            election.runIfLeader(lockName) { extraStarted.incrementAndGet() }
        }
        Thread.sleep(50)
        extraStarted.get() shouldBeEqualTo 0  // 슬롯 점유 중이므로 아직 실행 안됨

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
    fun `state - 2개 리더 활성 중 상태 정보가 정확하다`() {
        val lockName = randomLockName()
        val startLatch = CountDownLatch(2)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        repeat(2) {
            executor.submit {
                election.runIfLeader(lockName) {
                    startLatch.countDown()
                    holdLatch.await()
                }
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        // 2개 활성 중 상태 검증
        val activeState = election.state(lockName)
        activeState.activeCount shouldBeEqualTo 2
        activeState.availableSlots shouldBeEqualTo maxLeaders - 2
        activeState.isEmpty.shouldBeFalse()
        activeState.isFull.shouldBeFalse()

        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        // 모두 완료 후 초기 상태로 복귀
        election.activeCount(lockName) shouldBeEqualTo 0
        election.availableSlots(lockName) shouldBeEqualTo maxLeaders
    }

    @Test
    fun `state - maxLeaders 가 모두 활성이면 isFull=true`() {
        val lockName = randomLockName()
        val startLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders)

        repeat(maxLeaders) {
            executor.submit {
                election.runIfLeader(lockName) {
                    startLatch.countDown()
                    holdLatch.await()
                }
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        election.state(lockName).isFull.shouldBeTrue()
        election.availableSlots(lockName) shouldBeEqualTo 0

        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    // ── skip-behavior (ShedLock 방식): 슬롯 획득 실패 시 null 반환 ──────────

    @Test
    fun `runIfLeader - waitTime 내 슬롯 획득 실패 시 null 을 반환한다`() {
        val skipElection = LocalLeaderGroupElector(
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()
        val latch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        // maxLeaders=1 이므로 하나의 스레드가 슬롯을 점유하면 다음은 skip
        // 동일 election 인스턴스 사용해야 같은 Semaphore 를 공유함
        val holder = Thread {
            skipElection.runIfLeader(lockName) {
                latch.countDown()
                releaseLatch.await()
            }
        }.apply { start() }

        latch.await(2, TimeUnit.SECONDS)

        // 슬롯이 점유 중이므로 null 반환
        val result = skipElection.runIfLeader(lockName) { "should-skip" }
        result.shouldBeNull()

        releaseLatch.countDown()
        holder.join()
    }

    @Test
    fun `runIfLeader - 슬롯 해제 후 재시도 시 정상 실행된다`() {
        val shortWaitElection = LocalLeaderGroupElector(
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        )
        val lockName = randomLockName()

        val first = shortWaitElection.runIfLeader(lockName) { "first" }
        first shouldBeEqualTo "first"

        val second = shortWaitElection.runIfLeader(lockName) { "second" }
        second shouldBeEqualTo "second"
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
                election.runIfLeader(lockName) {
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    Thread.sleep(Random.nextLong(1, 5))
                    counter.incrementAndGet()
                }
            }
            .run()

        counter.get() shouldBeEqualTo numThreads * roundsPerThread
    }
}

package io.bluetape4k.leader.hazelcast

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HazelcastLeaderGroupElectionTest: AbstractHazelcastLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = 10.seconds,
        leaseTime = 60.seconds,
    )
    private val election by lazy { HazelcastLeaderGroupElector(hazelcastClient, options) }

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
        runCatching { election.runIfLeader(lockName) { throw LeaderGroupElectionException("실패") } }
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 빠른 종료 시 minLeaseTime 동안 Hazelcast 슬롯 TTL 을 보존한다`() {
        val lockName = randomName()
        val singleElection = HazelcastLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 100.milliseconds,
                leaseTime = 3.seconds,
                minLeaseTime = 2.seconds,
            )
        )

        singleElection.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"
        singleElection.runIfLeader(lockName) { "too-early" }.shouldBeNull()

        Thread.sleep(2_200)

        singleElection.runIfLeader(lockName) { "after-min" } shouldBeEqualTo "after-min"
    }

    @Test
    fun `runIfLeader - 모든 슬롯이 사용 중이면 waitTime 초과 시 null 을 반환한다`() {
        val shortWaitOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        val singleElection = HazelcastLeaderGroupElector(hazelcastClient, shortWaitOptions)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            singleElection.runIfLeader(lockName) {
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
                futureOf<Int> { throw LeaderGroupElectionException("실패") }
            }.join()
        }
        val result = election.runAsyncIfLeader(lockName) { futureOf { "복구 성공" } }.join()
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 그룹 슬롯 cleanup 이 실행된다`() {
        val lockName = randomName()
        val singleElection = HazelcastLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val executor = Executors.newSingleThreadExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val resultFuture = singleElection.runAsyncIfLeader(lockName, executor) {
                actionStarted.countDown()
                actionFuture
            }
            actionStarted.await(3, TimeUnit.SECONDS).shouldBeTrue()
            executor.shutdown()

            actionFuture.complete("done")

            resultFuture.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
            singleElection.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeaderResult - caller 취소를 action과 그룹 슬롯 cleanup에 전파한다`() {
        val lockName = randomName()
        val singleElection = HazelcastLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val actionStarted = CountDownLatch(1)
        val actionTerminal = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>().also { future ->
            future.whenComplete { _, _ -> actionTerminal.countDown() }
        }

        val result = singleElection.runAsyncIfLeaderResult(LeaderSlot(lockName, "hazelcast-group-cancel")) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(3, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        actionTerminal.await(3, TimeUnit.SECONDS).shouldBeTrue()
        actionFuture.isCancelled.shouldBeTrue()
        singleElection.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `runAsyncIfLeader - 두 번째 executor 제출 거부 후 획득한 슬롯을 정리한다`() {
        val lockName = randomName()
        val singleElection = HazelcastLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val actionInvoked = AtomicBoolean()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                singleElection.runAsyncIfLeader(lockName, executor) {
                    actionInvoked.set(true)
                    CompletableFuture.completedFuture("실행되면 안 됨")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            actionInvoked.get() shouldBeEqualTo false
            singleElection.runIfLeader(lockName) { "executor 거부 후 슬롯 복구" } shouldBeEqualTo
                    "executor 거부 후 슬롯 복구"
        } finally {
            worker.shutdownNow()
        }
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

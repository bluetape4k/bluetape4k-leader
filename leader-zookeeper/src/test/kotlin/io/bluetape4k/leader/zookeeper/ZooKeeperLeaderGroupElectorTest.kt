package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperLeaderGroupElectorTest: AbstractZooKeeperLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 30.seconds)
    private val elector by lazy { ZooKeeperLeaderGroupElector(curator, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = elector.runIfLeader(randomName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적인 lease 풀을 가진다`() {
        val result1 = elector.runIfLeader(randomName()) { "a" }
        val result2 = elector.runIfLeader(randomName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - 모든 lease 가 사용 중이면 waitTime 초과 시 null 을 반환한다`() {
        val singleOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        val singleElection = ZooKeeperLeaderGroupElector(curator, singleOptions)
        val lockName = randomName()
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            val blockingElection = ZooKeeperLeaderGroupElector(
                curator,
                singleOptions.copy(waitTime = 5.seconds)
            )
            blockingElection.runIfLeader(lockName) {
                acquired.countDown()
                release.await(5.seconds)
            }
        }

        try {
            acquired.await(2.seconds)

            val result = singleElection.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - group 획득 대기 interrupt 를 재전파한다`() {
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedException> {
                elector.runIfLeader(randomName()) { "should-not-run" }
            }
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        runCatching {
            elector.runIfLeader(lockName) {
                error("boom")
            }
        }

        val result = elector.runIfLeader(lockName) { "recovered" }
        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행한다`() {
        val result = elector.runAsyncIfLeader(randomName()) {
            completableFutureOf(42)
        }.join()

        result shouldBeEqualTo 42
    }

    @Test
    fun `runAsyncIfLeader - nullable 반환 future 취소가 action과 ZooKeeper group cleanup으로 전파된다`() {
        val lockName = randomName()
        val singleElector = ZooKeeperLeaderGroupElector(
            curator,
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds, leaseTime = 5.seconds),
        )
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = singleElector.runAsyncIfLeader(lockName, executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(3.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            randomSleep()
            actionFuture.isCancelled.shouldBeTrue()

            await atMost 5.seconds withPollInterval 100.milliseconds untilAsserted {
                singleElector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
            }
        } finally {
            actionFuture.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun `MultithreadingTester - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(options.maxLeaders * 4)
            .rounds(2)
            .add {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }

                    randomSleep()
                    executed.incrementAndGet()
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "동시 최대 실행 수=${peakConcurrent.get()}, 실행 수=${executed.get()}" }
        peakConcurrent.get() shouldBeEqualTo options.maxLeaders
        executed.get() shouldBeEqualTo options.maxLeaders * 4 * 2
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `StructuredTaskScopeTester - virtual thread 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(options.maxLeaders * 4 * 2)
            .add {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }

                    randomSleep()
                    executed.incrementAndGet()
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "동시 최대 실행 수=${peakConcurrent.get()}, 실행 수=${executed.get()}" }
        peakConcurrent.get() shouldBeEqualTo options.maxLeaders
        executed.get() shouldBeEqualTo options.maxLeaders * 4 * 2
    }
}

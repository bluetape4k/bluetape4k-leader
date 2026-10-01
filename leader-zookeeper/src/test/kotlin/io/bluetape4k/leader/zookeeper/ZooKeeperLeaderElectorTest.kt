package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
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

class ZooKeeperLeaderElectorTest: AbstractZooKeeperLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val election = ZooKeeperLeaderElector(curator)

        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - lock 이 이미 보유된 경우 waitTime 초과 시 null 을 반환한다`() {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
        val election = ZooKeeperLeaderElector(curator, options = shortWaitOptions)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            val blockingElection =
                ZooKeeperLeaderElector(curator, options = shortWaitOptions.copy(waitTime = 5.seconds))
            blockingElection.runIfLeader(lockName) {
                lockAcquired.countDown()
                releaseLock.await(3.seconds)
            }
        }

        try {
            lockAcquired.await(2.seconds)
            val result = election.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            releaseLock.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - 획득 대기 interrupt 를 재전파한다`() {
        val elector = ZooKeeperLeaderElector(curator)

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
    fun `runIfLeader - action 예외 후에도 lock 이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        val elector = ZooKeeperLeaderElector(curator)

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
        val election = ZooKeeperLeaderElector(curator)

        val result = election.runAsyncIfLeader(randomName()) {
            completableFutureOf(42)
        }.join()

        result shouldBeEqualTo 42
    }

    @Test
    fun `runAsyncIfLeader - nullable 반환 future 취소가 action과 ZooKeeper lock cleanup으로 전파된다`() {
        val lockName = randomName()
        val elector = ZooKeeperLeaderElector(
            curator,
            options = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds),
        )
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = elector.runAsyncIfLeader(lockName, executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(2.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            randomSleep()
            actionFuture.isCancelled.shouldBeTrue()

            await atMost 5.seconds withPollInterval 100.milliseconds untilAsserted {
                elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
            }
        } finally {
            actionFuture.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun `MultithreadingTester - 멀티스레드 경합에서 단일 리더만 실행된다`() {
        val lockName = randomName()
        val elector = ZooKeeperLeaderElector(curator)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(Runtimex.availableProcessors)
            .rounds(4)
            .add {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }

                    executed.incrementAndGet()
                    randomSleep()
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "동시 최대 Lock 수=${peakConcurrent.get()}, 실행 횟수=${executed.get()}" }
        executed.get() shouldBeEqualTo Runtimex.availableProcessors * 4
        peakConcurrent.get() shouldBeEqualTo 1
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `StructuredTaskScopeTester - virtual thread 경합에서 단일 리더만 실행된다`() {
        val lockName = randomName()
        val elector = ZooKeeperLeaderElector(curator)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(Runtimex.availableProcessors * 4)
            .add {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }

                    executed.incrementAndGet()
                    randomSleep()
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "동시 최대 Lock 수=${peakConcurrent.get()}, 실행 횟수=${executed.get()}" }
        executed.get() shouldBeEqualTo Runtimex.availableProcessors * 4
        peakConcurrent.get() shouldBeEqualTo 1
    }
}

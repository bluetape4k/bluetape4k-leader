package io.bluetape4k.leader.zookeeper

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionOptions
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperLeaderElectorTest: AbstractZooKeeperLeaderTest() {

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
            val blockingElection = ZooKeeperLeaderElector(curator, options = shortWaitOptions.copy(waitTime = 5.seconds))
            blockingElection.runIfLeader(lockName) {
                lockAcquired.countDown()
                releaseLock.await(3, TimeUnit.SECONDS)
            }
        }

        try {
            lockAcquired.await(2, TimeUnit.SECONDS)
            val result = election.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            releaseLock.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lock 이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        val election = ZooKeeperLeaderElector(curator)

        runCatching { election.runIfLeader(lockName) { error("boom") } }
        val result = election.runIfLeader(lockName) { "recovered" }

        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행한다`() {
        val election = ZooKeeperLeaderElector(curator)

        val result = election.runAsyncIfLeader(randomName()) {
            CompletableFuture.completedFuture(42)
        }.join()

        result shouldBeEqualTo 42
    }

    @Test
    fun `MultithreadingTester - 멀티스레드 경합에서 단일 리더만 실행된다`() {
        val lockName = randomName()
        val election = ZooKeeperLeaderElector(curator)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(8)
            .rounds(2)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    try {
                        executed.incrementAndGet()
                        Thread.sleep(10)
                    } finally {
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        executed.get() shouldBeGreaterThan 0
        peakConcurrent.get() shouldBeLessOrEqualTo 1
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `StructuredTaskScopeTester - virtual thread 경합에서 단일 리더만 실행된다`() {
        val lockName = randomName()
        val election = ZooKeeperLeaderElector(curator)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(16)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    try {
                        Thread.sleep(10)
                    } finally {
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        peakConcurrent.get() shouldBeLessOrEqualTo 1
    }
}

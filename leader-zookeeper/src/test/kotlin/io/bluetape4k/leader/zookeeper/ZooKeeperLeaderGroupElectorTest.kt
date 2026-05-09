package io.bluetape4k.leader.zookeeper

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
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

class ZooKeeperLeaderGroupElectorTest: AbstractZooKeeperLeaderTest() {

    private val options = LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 30.seconds)
    private val election by lazy { ZooKeeperLeaderGroupElector(curator, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적인 lease 풀을 가진다`() {
        val result1 = election.runIfLeader(randomName()) { "a" }
        val result2 = election.runIfLeader(randomName()) { "b" }

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
                release.await(5, TimeUnit.SECONDS)
            }
        }

        try {
            acquired.await(2, TimeUnit.SECONDS)
            val result = singleElection.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()

        runCatching { election.runIfLeader(lockName) { error("boom") } }
        val result = election.runIfLeader(lockName) { "recovered" }

        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행한다`() {
        val result = election.runAsyncIfLeader(randomName()) {
            CompletableFuture.completedFuture(42)
        }.join()

        result shouldBeEqualTo 42
    }

    @Test
    fun `MultithreadingTester - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
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
                    try {
                        Thread.sleep(20)
                    } finally {
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        peakConcurrent.get() shouldBeGreaterThan 0
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `StructuredTaskScopeTester - virtual thread 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(options.maxLeaders * 6)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    try {
                        Thread.sleep(20)
                    } finally {
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        peakConcurrent.get() shouldBeGreaterThan 0
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }
}

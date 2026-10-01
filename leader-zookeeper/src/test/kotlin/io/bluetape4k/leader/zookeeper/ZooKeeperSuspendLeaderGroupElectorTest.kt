package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperSuspendLeaderGroupElectorTest: AbstractZooKeeperLeaderTest() {

    companion object: KLogging()

    private val options = LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 30.seconds)
    private val elector by lazy { ZooKeeperSuspendLeaderGroupElector(curator, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runTest {
        val result = elector.runIfLeader(randomName()) {
            delay(10.milliseconds)
            "hello"
        }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 모든 lease 가 사용 중이면 waitTime 초과 시 null 을 반환한다`() = runTest {
        val singleOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        val singleElector = ZooKeeperSuspendLeaderGroupElector(curator, singleOptions)
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
            val result = singleElector.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() = runTest {
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
    fun `runIfLeader - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() = runTest {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        List(options.maxLeaders * 8) {
            async {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    delay(20.milliseconds)
                    currentConcurrent.decrementAndGet()
                }
            }.log("Job #$it")
        }.awaitAll()

        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @Test
    fun `SuspendedJobTester - 코루틴 job 경합에서 리더 수가 maxLeaders 를 초과하지 않는다`() = runTest {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        SuspendedJobTester()
            .rounds(options.maxLeaders * 6)
            .add {
                elector.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }

                    randomDelay()
                    executed.incrementAndGet()
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "동시 최대 실행 수=${peakConcurrent.get()}, 실행 수=${executed.get()}" }
        peakConcurrent.get() shouldBeEqualTo options.maxLeaders
        executed.get() shouldBeEqualTo options.maxLeaders * 6
    }
}

package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperSuspendLeaderElectorTest: AbstractZooKeeperLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runSuspendIO {
        ZooKeeperSuspendLeaderElector(curator).use { elector ->
            val result = elector.runIfLeader(randomName()) {
                delay(10.milliseconds)
                "hello"
            }
            result shouldBeEqualTo "hello"
        }
    }

    @Test
    fun `runIfLeader - lock 이 이미 보유된 경우 waitTime 초과 시 null 을 반환한다`() = runSuspendIO {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
        val elector = ZooKeeperSuspendLeaderElector(curator, options = shortWaitOptions)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            val blockingElection =
                ZooKeeperLeaderElector(curator, options = shortWaitOptions.copy(waitTime = 5.seconds))
            blockingElection.runIfLeader(lockName) {
                acquired.countDown()
                release.await(5.seconds)
            }
        }

        try {
            acquired.await(2.seconds)
            val result = elector.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            elector.close()
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() = runSuspendIO {
        val lockName = randomName()

        ZooKeeperSuspendLeaderElector(curator).use { elector ->
            runCatching {
                elector.runIfLeader(lockName) {
                    error("boom")
                }
            }

            elector.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
        }
    }

    @Test
    fun `SuspendedJobTester - 코루틴 job 경합에서 단일 리더만 실행된다`() = runSuspendIO {
        val lockName = randomName()
        ZooKeeperSuspendLeaderElector(curator).use { elector ->
            val currentConcurrent = AtomicInteger(0)
            val peakConcurrent = AtomicInteger(0)
            val executed = AtomicInteger(0)

            SuspendedJobTester()
                .rounds(16)
                .add {
                    elector.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }

                        executed.incrementAndGet()
                        randomDelay()
                        currentConcurrent.decrementAndGet()
                    }
                }
                .run()

            log.debug { "동시 최대 실행 수=${peakConcurrent.get()}, 실행 수=${executed.get()}" }
            peakConcurrent.get() shouldBeEqualTo 1
            executed.get() shouldBeEqualTo 16
        }
    }
}

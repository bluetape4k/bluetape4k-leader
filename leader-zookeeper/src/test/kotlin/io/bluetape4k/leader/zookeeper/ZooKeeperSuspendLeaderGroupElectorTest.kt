package io.bluetape4k.leader.zookeeper

import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperSuspendLeaderGroupElectorTest: AbstractZooKeeperLeaderTest() {

    private val options = LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 30.seconds)
    private val election by lazy { ZooKeeperSuspendLeaderGroupElector(curator, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runTest {
        val result = election.runIfLeader(randomName()) {
            delay(10.milliseconds)
            "hello"
        }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 모든 lease 가 사용 중이면 waitTime 초과 시 null 을 반환한다`() = runTest {
        val singleOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
        val singleElection = ZooKeeperSuspendLeaderGroupElector(curator, singleOptions)
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
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() = runTest {
        val lockName = randomName()

        runCatching { election.runIfLeader(lockName) { error("boom") } }
        val result = election.runIfLeader(lockName) { "recovered" }

        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `runIfLeader - 동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() = runTest {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        (1..options.maxLeaders * 8)
            .map {
                async {
                    election.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        delay(20.milliseconds)
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .awaitAll()

        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @Test
    fun `SuspendedJobTester - 코루틴 job 경합에서 리더 수가 maxLeaders 를 초과하지 않는다`() = runTest {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        SuspendedJobTester()
            .workers(options.maxLeaders * 4)
            .rounds(options.maxLeaders * 6)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    try {
                        delay(20.milliseconds)
                    } finally {
                        currentConcurrent.decrementAndGet()
                    }
                }
            }
            .run()

        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }
}

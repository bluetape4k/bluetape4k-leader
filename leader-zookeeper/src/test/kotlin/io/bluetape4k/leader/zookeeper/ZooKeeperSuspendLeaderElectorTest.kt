package io.bluetape4k.leader.zookeeper

import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.leader.LeaderElectionOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperSuspendLeaderElectorTest: AbstractZooKeeperLeaderTest() {

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runTest {
        val election = ZooKeeperSuspendLeaderElector(curator)

        val result = try {
            election.runIfLeader(randomName()) {
                delay(10.milliseconds)
                "hello"
            }
        } finally {
            election.close()
        }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - lock 이 이미 보유된 경우 waitTime 초과 시 null 을 반환한다`() = runTest {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
        val election = ZooKeeperSuspendLeaderElector(curator, options = shortWaitOptions)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            val blockingElection = ZooKeeperLeaderElector(curator, options = shortWaitOptions.copy(waitTime = 5.seconds))
            blockingElection.runIfLeader(lockName) {
                acquired.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        try {
            acquired.await(2, TimeUnit.SECONDS)
            val result = election.runIfLeader(lockName) { "should-skip" }
            result.shouldBeNull()
        } finally {
            election.close()
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - action 예외 후에도 lease 가 반환되어 다음 호출이 성공한다`() = runTest {
        val lockName = randomName()
        val election = ZooKeeperSuspendLeaderElector(curator)

        val result = try {
            runCatching { election.runIfLeader(lockName) { error("boom") } }
            election.runIfLeader(lockName) { "recovered" }
        } finally {
            election.close()
        }

        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `suspend elector opens cleanup scope immediately after acquisition`() {
        val source = Path.of("src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperSuspendLeaderElector.kt")
            .toFile()
            .readText()

        source.cleanupScopeStartsImmediatelyAfterAcquire().shouldBeTrue()
    }

    @Test
    fun `SuspendedJobTester - 코루틴 job 경합에서 단일 리더만 실행된다`() = runTest {
        val lockName = randomName()
        val election = ZooKeeperSuspendLeaderElector(curator)
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        try {
            SuspendedJobTester()
                .workers(12)
                .rounds(16)
                .add {
                    election.runIfLeader(lockName) {
                        val current = currentConcurrent.incrementAndGet()
                        peakConcurrent.updateAndGet { max(it, current) }
                        try {
                            executed.incrementAndGet()
                            delay(10.milliseconds)
                        } finally {
                            currentConcurrent.decrementAndGet()
                        }
                    }
                }
                .run()

            executed.get() shouldBeGreaterThan 0
            peakConcurrent.get() shouldBeLessOrEqualTo 1
        } finally {
            election.close()
        }
    }

    private fun String.cleanupScopeStartsImmediatelyAfterAcquire(): Boolean {
        val acquired = indexOf("log.debug { \"ZooKeeper suspend leader lock 획득 성공. path=${'$'}path\" }")
        val cleanupScope = indexOf("var watchdog: AutoCloseable? = null", startIndex = acquired.coerceAtLeast(0))
        val tryStart = indexOf("try {", startIndex = cleanupScope.coerceAtLeast(0))
        val watchdogStart = indexOf("LeaderLeaseAutoExtender.start", startIndex = tryStart.coerceAtLeast(0))
        val finallyStart = indexOf("} finally {", startIndex = watchdogStart.coerceAtLeast(0))
        val watchdogClose = indexOf("watchdog?.close()", startIndex = finallyStart.coerceAtLeast(0))
        val release = indexOf("mutex.release()", startIndex = watchdogClose.coerceAtLeast(0))
        return listOf(acquired, cleanupScope, tryStart, watchdogStart, finallyStart, watchdogClose, release).all { it >= 0 } &&
            acquired < cleanupScope &&
            cleanupScope < tryStart &&
            tryStart < watchdogStart &&
            watchdogStart < finallyStart &&
            finallyStart < watchdogClose &&
            watchdogClose < release
    }
}

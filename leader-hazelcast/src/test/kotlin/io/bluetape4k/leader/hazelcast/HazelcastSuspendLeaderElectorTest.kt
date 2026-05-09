package io.bluetape4k.leader.hazelcast

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HazelcastSuspendLeaderElectorTest: AbstractHazelcastLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runTest {
        val election = HazelcastSuspendLeaderElector(hazelcastClient)
        val result = election.runIfLeader(randomName()) {
            delay(10.milliseconds)
            "hello"
        }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - lock 이 이미 보유된 경우 waitTime 초과 시 null 을 반환한다`() = runTest {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
        val election = HazelcastSuspendLeaderElector(hazelcastClient, shortWaitOptions)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            val blockingElection = HazelcastLeaderElector(hazelcastClient, shortWaitOptions)
            blockingElection.runIfLeader(lockName) {
                lockAcquired.countDown()
                releaseLock.await(3, TimeUnit.SECONDS)
            }
        }

        try {
            lockAcquired.await(2, TimeUnit.SECONDS)
            val result = election.runIfLeader(lockName) { 1 }
            result.shouldBeNull()
        } finally {
            releaseLock.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - 코루틴 내 스레드 전환이 발생해도 lock 이 올바르게 해제된다`() = runTest {
        val lockName = randomName()
        val election = HazelcastSuspendLeaderElector(hazelcastClient)

        // 여러 번 실행하여 lock 해제 확인
        repeat(5) {
            election.runIfLeader(lockName) {
                delay(10.milliseconds)
                "done-$it"
            }
        }

        // 마지막 결과가 정상 반환되어야 함
        val result = election.runIfLeader(lockName) { "final" }
        result shouldBeEqualTo "final"
    }

    @Test
    fun `runIfLeader - 코루틴 환경에서 순차적으로 leader suspend 작업이 실행된다`() = runSuspendIO {
        val lockName = randomName()
        val election = HazelcastSuspendLeaderElector(hazelcastClient)
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        SuspendedJobTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "suspend 작업 1. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    delay(Random.nextLong(5, 10).milliseconds)
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "suspend 작업 2. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    delay(Random.nextLong(5, 10).milliseconds)
                }
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }
}

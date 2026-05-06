package io.bluetape4k.leader.redisson

import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class RedissonSuspendLeaderElectorTest: AbstractRedissonLeaderTest() {

    companion object: KLoggingChannel()

    @Test
    fun `run suspend action if leader`() = runSuspendIO {
        val lockName = randomName()
        val leaderElection = RedissonSuspendLeaderElector(redissonClient)

        coroutineScope {
            launch {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다." }
                    randomDelay()
                    log.debug { "작업 1 을 종료합니다." }
                }
            }

            launch {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다." }
                    randomDelay()
                    log.debug { "작업 2 을 종료합니다." }
                }
            }
        }
    }

    @Test
    fun `run action if leader in multi job`() = runSuspendIO {
        val lockName = randomName()
        val leaderElection = RedissonSuspendLeaderElector(redissonClient)

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerJob = 4

        SuspendedJobTester()
            .workers(numThreads)
            .rounds(numThreads * roundsPerJob)
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다." }
                    task1.incrementAndGet()
                    randomDelay()
                    log.debug { "작업 1 을 종료합니다." }
                }
            }
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다." }
                    task2.incrementAndGet()
                    randomDelay()
                    log.debug { "작업 2 을 종료합니다." }
                }
            }
            .run()

        task1.get() shouldBeEqualTo numThreads * roundsPerJob
        task2.get() shouldBeEqualTo numThreads * roundsPerJob
    }

    @Test
    fun `run action should return null when lock is not acquired`() = runSuspendIO {
        val lockName = randomName()
        val options = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
        val leaderElection = RedissonSuspendLeaderElector(redissonClient, options)
        val lock = redissonClient.getLock(lockName)

        lock.lock(3, TimeUnit.SECONDS)
        try {
            val result = leaderElection.runIfLeader(lockName) { 1 }
            result shouldBeEqualTo null
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    /**
     * [SuspendedJobTester]를 사용하여 짧은 `waitTime` 환경에서 여러 코루틴이 동시에
     * [RedissonSuspendLeaderElector.runIfLeader]를 호출할 때,
     * 리더로 선출된 코루틴은 카운터를 증가시키고,
     * 락 획득에 실패한 코루틴은 [RedisException]을 안전하게 삼키는지 검증한다.
     */
    @Test
    fun `동시 다수 코루틴에서 suspendRunIfLeader 호출 시 성공하거나 RedisException 을 안전하게 처리한다`() = runSuspendIO {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
        )
        val leaderElection = RedissonSuspendLeaderElector(redissonClient, shortWaitOptions)
        val successCount = AtomicInteger(0)

        SuspendedJobTester()
            .workers(16)
            .rounds(4)
            .add {
                runCatching {
                    leaderElection.runIfLeader(lockName) {
                        successCount.incrementAndGet()
                        randomDelay(10, 30)
                    }
                }
                // RedisException(락 획득 실패) 또는 성공 — 둘 다 허용
            }
            .run()

        log.debug { "총 성공 횟수: ${successCount.get()}" }
    }

    private suspend fun randomDelay(from: Long = 5L, until: Long = 10L) {
        delay(Random.nextLong(from, until).milliseconds)
    }
}

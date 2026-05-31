package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class RedissonWatchdogJobRunnerTest: AbstractRedissonWatchdogTest() {

    @Test
    fun `single runner executes leader job`() {
        val executions = AtomicInteger(0)
        val report = RedissonWatchdogJobRunner(
            nodeId = "node-a",
            redissonClient = redissonClient,
            lockName = randomLockName(),
        ).runJob {
            executions.incrementAndGet()
        }

        report.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
        report.jobThreadName.shouldNotBeNull()
        executions.get() shouldBeEqualTo 1
    }

    @Test
    fun `watchdog keeps long-running leader job protected beyond initial lease`() {
        val options = RedissonWatchdogJobRunner.watchdogOptions(
            waitTime = 100.milliseconds,
            leaseTime = 250.milliseconds,
        )
        val lockName = randomLockName()
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val contenderExecutions = AtomicInteger(0)

        try {
            val leader = RedissonWatchdogJobRunner("node-a", redissonClient, lockName, options)
            val contender = RedissonWatchdogJobRunner("node-b", redissonClient, lockName, options)

            val leaderFuture = executor.submit<RedissonWatchdogNodeReport> {
                leader.runJob {
                    leaderStarted.countDown()
                    releaseLeader.await(2, TimeUnit.SECONDS)
                }
            }

            leaderStarted.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
            Thread.sleep(600)

            val skipped = contender.runJob {
                contenderExecutions.incrementAndGet()
            }

            releaseLeader.countDown()
            val leaderReport = leaderFuture.get(2, TimeUnit.SECONDS)
            val reacquired = contender.runJob {
                contenderExecutions.incrementAndGet()
            }

            leaderReport.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
            skipped.status shouldBeEqualTo RedissonWatchdogStatus.SKIPPED
            reacquired.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
            contenderExecutions.get() shouldBeEqualTo 1
        } finally {
            releaseLeader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `completed leader job releases lock for the next node`() {
        val lockName = randomLockName()
        val first = RedissonWatchdogJobRunner("node-a", redissonClient, lockName).runJob { }
        val second = RedissonWatchdogJobRunner("node-b", redissonClient, lockName).runJob { }

        first.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
        second.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
    }
}

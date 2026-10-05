package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedissonWatchdogJobRunnerTest: AbstractRedissonWatchdogTest() {

    companion object: KLogging()

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

        log.debug { "report=$report" }
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
                    releaseLeader.await(2.seconds)
                }
            }

            leaderStarted.await(1.seconds).shouldBeTrue()
            Thread.sleep(600)

            val skipped = contender.runJob {
                contenderExecutions.incrementAndGet()
            }

            releaseLeader.countDown()
            val leaderReport = leaderFuture.get(3.seconds)
            log.debug { "leaderReport=$leaderReport" }

            val reacquired = contender.runJob {
                contenderExecutions.incrementAndGet()
            }
            log.debug { "reacquired=$reacquired" }

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

        log.debug { "first=$first" }
        log.debug { "second=$second" }
        first.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
        second.status shouldBeEqualTo RedissonWatchdogStatus.ELECTED
    }

    @Test
    fun `동시에 4개의 JobRunner를 실행시켜도 Node 별로 leader 를 선출한다`() {
        val lockName = randomLockName()
        val runners = List(4) {
            RedissonWatchdogJobRunner("node-$it", redissonClient, lockName)
        }
        val executor = Executors.newFixedThreadPool(4)

        val futures = runners.map { runner ->
            executor.submit<RedissonWatchdogNodeReport> {
                runner.runJob {
                    log.debug { "Execute in ${runner.nodeId}" }
                }
            }
        }
        val results = futures.map { it.get(3.seconds) }
        results.forEach { log.debug { "report=$it" } }
    }
}

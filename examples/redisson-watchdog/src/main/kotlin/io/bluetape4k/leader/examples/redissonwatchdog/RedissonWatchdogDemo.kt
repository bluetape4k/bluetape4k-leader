package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import org.redisson.Redisson
import org.redisson.config.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demonstrates a long-running Redisson-backed job protected by lease auto-extension.
 */
object RedissonWatchdogDemo: KLogging() {

    @JvmStatic
    fun main(args: Array<String>) {
        val redis = RedisServer.Launcher.redis
        val redisson = Redisson.create(
            Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(4)
                    .setConnectionMinimumIdleSize(1)
            }
        ).also {
            ShutdownQueue.register { it.shutdown() }
        }

        val options = RedissonWatchdogJobRunner.watchdogOptions(
            waitTime = 100.milliseconds,
            leaseTime = 300.milliseconds,
        )
        val lockName = "redisson-watchdog-demo"
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            log.info { "=== Redisson watchdog demo start ===" }

            val leader = RedissonWatchdogJobRunner("node-1", redisson, lockName, options)
            val contender = RedissonWatchdogJobRunner("node-2", redisson, lockName, options)

            val leaderFuture = executor.submit<RedissonWatchdogNodeReport> {
                leader.runJob {
                    leaderStarted.countDown()
                    releaseLeader.await(2, TimeUnit.SECONDS)
                }
            }

            leaderStarted.await(1, TimeUnit.SECONDS)
            Thread.sleep(700)

            val contenderDuringLongJob = contender.runJob {
                error("contender must not run while the leader watchdog keeps the lease alive")
            }

            releaseLeader.countDown()
            val leaderReport = leaderFuture.get(2, TimeUnit.SECONDS)
            val contenderAfterRelease = contender.runJob {
                log.info { "[node-2] acquired leadership after node-1 released the lock" }
            }

            log.info { "leaderReport=$leaderReport" }
            log.info { "contenderDuringLongJob=$contenderDuringLongJob" }
            log.info { "contenderAfterRelease=$contenderAfterRelease" }
            log.info { "=== Redisson watchdog demo complete ===" }
        } finally {
            releaseLeader.countDown()
            executor.shutdownNow()
            redisson.shutdown()
        }
    }
}

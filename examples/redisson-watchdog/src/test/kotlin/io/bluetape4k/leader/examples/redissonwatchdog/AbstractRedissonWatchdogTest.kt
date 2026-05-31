package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedissonWatchdogTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val redissonClient: RedissonClient by lazy {
            Redisson.create(
                Config().apply {
                    useSingleServer()
                        .setAddress(redis.url)
                        .setConnectionPoolSize(8)
                        .setConnectionMinimumIdleSize(2)
                }
            ).apply {
                ShutdownQueue.register { shutdown() }
            }
        }
    }

    protected fun randomLockName(): String = "redisson-watchdog:${Base58.randomString(8)}"
}

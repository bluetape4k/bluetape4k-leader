package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedissonWatchdogTest {

    companion object: KLogging() {
        val redis by lazy { RedisServer.Launcher.redis }

        //        val redissonClient: RedissonClient by lazy {
//            Redisson.create(
//                Config().apply {
//                    useSingleServer()
//                        .setAddress(redis.url)
//                        .setConnectionPoolSize(8)
//                        .setConnectionMinimumIdleSize(2)
//                }
//            ).apply {
//                ShutdownQueue.register { shutdown() }
//            }
//        }
        val redissonClient by lazy {
            RedisServer.Launcher.RedissonLib.getRedisson(redis.url)
        }
    }

    protected fun randomLockName(): String = "redisson-watchdog:${Base58.randomString(8)}"
}

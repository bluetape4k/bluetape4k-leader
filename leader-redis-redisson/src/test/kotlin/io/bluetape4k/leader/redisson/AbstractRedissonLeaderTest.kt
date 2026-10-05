package io.bluetape4k.leader.redisson

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import kotlinx.coroutines.delay
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

abstract class AbstractRedissonLeaderTest {

    companion object: KLogging() {
        val redis by lazy { RedisServer.Launcher.redis }

        @JvmStatic
        val redisUrl: String get() = redis.url

        @JvmStatic
        val redissonClient: RedissonClient by lazy {
            val config = Config().apply {
                useSingleServer()
                    .setAddress(redisUrl)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            }
            Redisson.create(config).apply {
                ShutdownQueue.register { shutdown() }
            }
        }
    }

    protected fun randomName(): String = "leader-test:${Base58.randomString(8)}"

    protected suspend fun randomDelay(from: Long = 5L, until: Long = 10L) {
        delay(Random.nextLong(from, until).milliseconds)
    }

    protected fun randomSleep(from: Long = 5L, until: Long = 10L) {
        Thread.sleep(Random.nextLong(from, until))
    }
}

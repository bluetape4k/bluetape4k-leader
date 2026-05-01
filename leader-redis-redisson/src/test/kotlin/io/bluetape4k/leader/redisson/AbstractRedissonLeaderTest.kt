package io.bluetape4k.leader.redisson

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedissonLeaderTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val redisUrl: String get() = redis.url

        val redissonClient: RedissonClient by lazy {
            val config = Config().apply {
                useSingleServer()
                    .setAddress(redisUrl)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            }
            Redisson.create(config)
        }

        fun randomName(): String = "leader-test:${Base58.randomString(8)}"
    }
}

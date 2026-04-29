package io.bluetape4k.leader.redisson

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.util.UUID

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

        fun randomName(): String = "leader-test:${UUID.randomUUID().toString().take(8)}"
    }

    @BeforeAll
    fun setupRedisson() {
        redissonClient.getAtomicLong("_init").get()
    }

    @AfterAll
    fun teardownRedisson() {
        // ShutdownQueue 에 등록된 컨테이너는 JVM 종료 시 자동 정리됨
    }
}

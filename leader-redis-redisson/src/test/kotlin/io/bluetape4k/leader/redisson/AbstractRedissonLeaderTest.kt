package io.bluetape4k.leader.redisson

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.testcontainers.containers.GenericContainer
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedissonLeaderTest {

    companion object: KLogging() {
        private val redisContainer: GenericContainer<*> =
            GenericContainer("redis:7-alpine").withExposedPorts(6379)

        init {
            redisContainer.start()
        }

        val redisUrl: String
            get() = "redis://${redisContainer.host}:${redisContainer.getMappedPort(6379)}"

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
        // 컨테이너는 JVM 종료 시 자동 정리됨 (companion object lazy)
    }
}

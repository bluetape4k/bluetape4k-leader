package io.bluetape4k.leader.lettuce

import io.bluetape4k.logging.KLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLettuceLeaderTest {

    companion object: KLogging() {
        private val redisContainer: GenericContainer<*> =
            GenericContainer("redis:7-alpine").withExposedPorts(6379)

        init {
            redisContainer.start()
        }

        val redisUri: String
            get() = "redis://${redisContainer.host}:${redisContainer.getMappedPort(6379)}"

        fun randomName(): String = "leader-test:${UUID.randomUUID().toString().take(8)}"
    }

    protected lateinit var client: RedisClient
    protected lateinit var connection: StatefulRedisConnection<String, String>

    @BeforeAll
    fun setupClient() {
        client = RedisClient.create(redisUri)
        connection = client.connect(StringCodec.UTF8)
    }

    @AfterAll
    fun teardownClient() {
        runCatching { connection.close() }
        runCatching { client.shutdown() }
    }
}

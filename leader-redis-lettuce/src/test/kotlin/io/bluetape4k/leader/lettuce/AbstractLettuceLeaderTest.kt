package io.bluetape4k.leader.lettuce

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLettuceLeaderTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val redisUri: String get() = redis.url

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

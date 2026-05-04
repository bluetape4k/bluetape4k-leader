package io.bluetape4k.leader.lettuce

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec

abstract class AbstractLettuceLeaderTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val redisUri: String get() = redis.url

        val client: RedisClient by lazy {
            RedisClient.create(redisUri).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        val connection: StatefulRedisConnection<String, String> by lazy {
            client.connect(StringCodec.UTF8).also {
                ShutdownQueue.register { it.closeSafe() }
            }
        }
    }

    protected fun randomName(): String = "leader-test:${Base58.randomString(8)}"
}

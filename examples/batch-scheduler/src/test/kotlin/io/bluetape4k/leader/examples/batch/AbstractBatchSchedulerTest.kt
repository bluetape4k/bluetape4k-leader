package io.bluetape4k.leader.examples.batch

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec

abstract class AbstractBatchSchedulerTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val client: RedisClient by lazy {
            RedisClient.create(redis.url).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        fun newConnection(): StatefulRedisConnection<String, String> =
            client.connect(StringCodec.UTF8).also {
                ShutdownQueue.register { it.closeSafe() }
            }
    }

    protected fun randomLockName(): String = "batch-test:${Base58.randomString(8)}"
}

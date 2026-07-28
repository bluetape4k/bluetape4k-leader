package io.bluetape4k.leader.examples.ktor

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec

/**
 * `AbstractKtorAppTest`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
abstract class AbstractKtorAppTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis

        val redisUrl: String get() = redis.url

        val client: RedisClient by lazy {
            RedisClient.create(redisUrl).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        fun newConnection(): StatefulRedisConnection<String, String> =
            client.connect(StringCodec.UTF8).also {
                ShutdownQueue.register { it.closeSafe() }
            }
    }

    protected fun randomLockName(): String = "examples-ktor-app:${Base58.randomString(8)}"
}

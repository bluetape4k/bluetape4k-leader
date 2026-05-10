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
 * `examples/ktor-app` 통합 테스트의 공통 베이스 클래스.
 *
 * ## 동작/계약
 * - `bluetape4k-testcontainers` 의 [RedisServer.Launcher.redis] singleton 을 공유한다.
 * - Lettuce [RedisClient] 는 lazy 로 1회 생성되며, JVM 종료 시 [ShutdownQueue] 를 통해 정리된다.
 * - [newConnection] 은 매 호출마다 새 [StatefulRedisConnection] 을 발급한다 — 다중 인스턴스 시뮬레이션 시
 *   인스턴스별 별도 connection 을 보장하기 위함 (E1 batch-scheduler 와 동일 패턴).
 * - 각 테스트는 [randomLockName] 으로 테스트 간 충돌 없는 고유 lock 이름을 발급한다.
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

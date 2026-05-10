package io.bluetape4k.leader.ktor

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

/**
 * `leader-ktor` 통합 테스트의 공통 베이스 클래스입니다.
 *
 * ## 동작/계약
 * - `bluetape4k-testcontainers` 의 [RedisServer.Launcher.redis] singleton 을 사용합니다.
 * - [RedissonClient] 는 lazy 로 1회 생성되며, JVM 종료 시 [ShutdownQueue] 를 통해 정리됩니다.
 * - 각 테스트는 [randomName] 으로 충돌하지 않는 고유 lock 이름을 발급받습니다.
 */
abstract class AbstractLeaderKtorTest {

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
            Redisson.create(config).apply {
                ShutdownQueue.register { shutdown() }
            }
        }
    }

    protected fun randomName(): String = "leader-ktor-test:${Base58.randomString(8)}"
}

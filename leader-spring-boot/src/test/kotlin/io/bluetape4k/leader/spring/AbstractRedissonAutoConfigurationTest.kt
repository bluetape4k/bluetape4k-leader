package io.bluetape4k.leader.spring

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

/**
 * Redisson 통합 테스트 base. `bluetape4k-testcontainers`의 [RedisServer.Launcher.redis] 를 통해
 * 단일 Redis 컨테이너를 모듈 전역으로 공유합니다.
 */
abstract class AbstractRedissonAutoConfigurationTest {

    companion object: KLogging() {
        @JvmStatic
        protected val redis: RedisServer = RedisServer.Launcher.redis

        @JvmStatic
        protected val redisUrl: String get() = redis.url

        @JvmStatic
        protected fun newRedissonClient(): RedissonClient =
            Redisson.create(newRedissonConfig())

        @JvmStatic
        fun newRedissonConfig(): Config = Config().apply {
            useSingleServer()
                .setAddress(redisUrl)
                .setConnectionPoolSize(4)
                .setConnectionMinimumIdleSize(1)
        }
    }
}

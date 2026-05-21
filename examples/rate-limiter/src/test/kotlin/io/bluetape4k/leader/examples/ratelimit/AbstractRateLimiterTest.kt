package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer

abstract class AbstractRateLimiterTest {

    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis
    }
}

package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractAsyncLeaderElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for async [RedissonLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonAsyncLeaderElectorLeaderIdContractTest : AbstractAsyncLeaderElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): AsyncLeaderElector =
        RedissonLeaderElector(AbstractRedissonLeaderTest.redissonClient, options)
}

package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractLeaderElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for blocking [RedissonLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonLeaderElectorLeaderIdContractTest : AbstractLeaderElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): LeaderElector =
        RedissonLeaderElector(AbstractRedissonLeaderTest.redissonClient, options)
}

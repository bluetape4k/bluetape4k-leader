package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractLeaderGroupElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for blocking [RedissonLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        RedissonLeaderGroupElector(AbstractRedissonLeaderTest.redissonClient, options)
}

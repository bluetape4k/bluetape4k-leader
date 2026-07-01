package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractAsyncLeaderGroupElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for async [RedissonLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonAsyncLeaderGroupElectorLeaderIdContractTest : AbstractAsyncLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): AsyncLeaderGroupElector =
        RedissonLeaderGroupElector(AbstractRedissonLeaderTest.redissonClient, options)
}

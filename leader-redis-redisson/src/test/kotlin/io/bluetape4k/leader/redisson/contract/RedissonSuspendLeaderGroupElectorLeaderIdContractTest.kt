package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLeaderGroupElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for coroutine [RedissonSuspendLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLoggingChannel() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(AbstractRedissonLeaderTest.redissonClient, options)
}

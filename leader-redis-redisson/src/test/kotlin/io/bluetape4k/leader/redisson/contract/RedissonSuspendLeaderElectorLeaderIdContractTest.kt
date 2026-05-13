package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLeaderElectorLeaderIdContractTest] Redisson backend implementation.
 *
 * Verifies slot-aware audit identity propagation for suspend [RedissonSuspendLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {

    companion object : KLoggingChannel() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        RedissonSuspendLeaderElector(AbstractRedissonLeaderTest.redissonClient, options)
}

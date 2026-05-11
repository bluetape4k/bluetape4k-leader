package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 Redisson backend 구현 — T8 PR 3 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonSuspendGroupLockExtenderContractTest: AbstractSuspendGroupLockExtenderContractTest() {

    companion object: KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override val elector: SuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(
            AbstractRedissonLeaderTest.redissonClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 Redisson backend 구현 — T8 PR 3 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonSuspendLockExtenderContractTest: AbstractSuspendLockExtenderContractTest() {

    companion object: KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override val elector: SuspendLeaderElector =
        RedissonSuspendLeaderElector(AbstractRedissonLeaderTest.redissonClient)
}

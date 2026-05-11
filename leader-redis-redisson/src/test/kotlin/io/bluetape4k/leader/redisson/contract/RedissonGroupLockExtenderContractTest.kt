package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 Redisson backend 구현 — T8 PR 3 (Issue #79).
 *
 * `maxLeaders = 2` 로 기본 설정. `RPermitExpirableSemaphore.updateLeaseTime` 으로 extend 수행.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonGroupLockExtenderContractTest: AbstractGroupLockExtenderContractTest() {

    companion object: KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override val elector: LeaderGroupElector =
        RedissonLeaderGroupElector(
            AbstractRedissonLeaderTest.redissonClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

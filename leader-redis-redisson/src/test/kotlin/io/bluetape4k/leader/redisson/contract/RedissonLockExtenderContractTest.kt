package io.bluetape4k.leader.redisson.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 Redisson backend 구현 — T8 PR 3 (Issue #79).
 *
 * Testcontainers `bluetape4k-testcontainers` 의 [io.bluetape4k.testcontainers.storage.RedisServer.Launcher.redis]
 * singleton 을 사용 — JVM 당 1회만 spin-up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonLockExtenderContractTest: AbstractSyncLockExtenderContractTest() {

    companion object: KLogging() {
        val redis = AbstractRedissonLeaderTest.redis
    }

    override val elector: LeaderElector =
        RedissonLeaderElector(AbstractRedissonLeaderTest.redissonClient)
}

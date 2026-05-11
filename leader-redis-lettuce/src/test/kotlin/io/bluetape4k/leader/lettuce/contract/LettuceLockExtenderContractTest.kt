package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 Lettuce backend 구현 — T7 PR 2 (Issue #79).
 *
 * Testcontainers `bluetape4k-testcontainers` 의 [io.bluetape4k.testcontainers.storage.RedisServer.Launcher.redis]
 * singleton 을 사용 — JVM 당 1회만 spin-up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override val elector: LeaderElector =
        LettuceLeaderElector(AbstractLettuceLeaderTest.connection)
}

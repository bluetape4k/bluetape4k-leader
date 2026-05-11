package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 Lettuce backend 구현 — T7 PR 2 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override val elector: SuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(
            AbstractLettuceLeaderTest.connection,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

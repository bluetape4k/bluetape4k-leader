package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLeaderGroupElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for coroutine [LettuceSuspendLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLoggingChannel() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(AbstractLettuceLeaderTest.connection, options)
}

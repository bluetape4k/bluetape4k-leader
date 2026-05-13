package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLeaderElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for suspend [LettuceSuspendLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {

    companion object : KLoggingChannel() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        LettuceSuspendLeaderElector(AbstractLettuceLeaderTest.connection, options)
}

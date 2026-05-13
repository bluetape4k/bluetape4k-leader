package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractLeaderElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for blocking [LettuceLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceLeaderElectorLeaderIdContractTest : AbstractLeaderElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): LeaderElector =
        LettuceLeaderElector(AbstractLettuceLeaderTest.connection, options)
}

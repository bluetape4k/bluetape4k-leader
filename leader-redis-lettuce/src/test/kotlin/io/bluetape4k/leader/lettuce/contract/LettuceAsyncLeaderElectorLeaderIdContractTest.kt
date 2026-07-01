package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractAsyncLeaderElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for async [LettuceLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceAsyncLeaderElectorLeaderIdContractTest : AbstractAsyncLeaderElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderElectionOptions): AsyncLeaderElector =
        LettuceLeaderElector(AbstractLettuceLeaderTest.connection, options)
}

package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractLeaderGroupElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for blocking [LettuceLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LettuceLeaderGroupElector(AbstractLettuceLeaderTest.connection, options)
}

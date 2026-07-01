package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractAsyncLeaderGroupElectorLeaderIdContractTest] Lettuce backend implementation.
 *
 * Verifies slot-aware audit identity propagation for async [LettuceLeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceAsyncLeaderGroupElectorLeaderIdContractTest : AbstractAsyncLeaderGroupElectorLeaderIdContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override fun createElector(options: LeaderGroupElectionOptions): AsyncLeaderGroupElector =
        LettuceLeaderGroupElector(AbstractLettuceLeaderTest.connection, options)
}

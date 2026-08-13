package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderGroupElectorLeaderIdContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul async group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulAsyncLeaderGroupElectorLeaderIdContractTest : AbstractAsyncLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): AsyncLeaderGroupElector =
        ConsulLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul blocking group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ConsulLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

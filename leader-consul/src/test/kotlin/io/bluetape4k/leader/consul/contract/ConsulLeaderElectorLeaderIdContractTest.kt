package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractLeaderElectorLeaderIdContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul blocking leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderElectorLeaderIdContractTest : AbstractLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): LeaderElector =
        ConsulLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(
                leaderOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

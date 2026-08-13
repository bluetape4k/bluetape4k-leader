package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElector
import io.bluetape4k.leader.contract.AbstractSuspendLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import org.junit.jupiter.api.TestInstance

/**
 * Consul suspend leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        ConsulSuspendLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(
                leaderOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

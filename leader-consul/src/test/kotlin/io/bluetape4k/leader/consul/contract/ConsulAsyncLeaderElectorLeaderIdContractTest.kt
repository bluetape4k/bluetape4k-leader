package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderElectorLeaderIdContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul async leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulAsyncLeaderElectorLeaderIdContractTest : AbstractAsyncLeaderElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderElectionOptions): AsyncLeaderElector =
        ConsulLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(
                leaderOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElector
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance

/**
 * Consul suspend group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

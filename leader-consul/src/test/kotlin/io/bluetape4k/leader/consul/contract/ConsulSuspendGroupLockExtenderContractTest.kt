package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElector
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance

/**
 * Consul suspend group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {
    override val elector: SuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

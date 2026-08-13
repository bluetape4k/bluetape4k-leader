package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul blocking group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {
    override val elector: LeaderGroupElector =
        ConsulLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
}

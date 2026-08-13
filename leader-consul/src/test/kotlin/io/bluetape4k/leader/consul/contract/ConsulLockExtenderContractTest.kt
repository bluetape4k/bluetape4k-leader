package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import org.junit.jupiter.api.TestInstance

/**
 * Consul blocking LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {
    override val elector: LeaderElector =
        ConsulLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(keyPrefix = ConsulContractSupport.keyPrefix()),
        )
}

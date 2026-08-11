package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElector
import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import org.junit.jupiter.api.TestInstance

/**
 * Consul suspend LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {
    override val elector: SuspendLeaderElector =
        ConsulSuspendLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(keyPrefix = ConsulContractSupport.keyPrefix()),
        )
}

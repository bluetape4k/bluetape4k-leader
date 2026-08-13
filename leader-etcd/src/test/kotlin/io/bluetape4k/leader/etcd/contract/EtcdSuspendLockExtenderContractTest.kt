package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector
import org.junit.jupiter.api.TestInstance

/**
 * etcd suspend LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {
    override val elector: SuspendLeaderElector =
        EtcdSuspendLeaderElector(
            EtcdContractSupport.client,
            EtcdLeaderElectionOptions(keyPrefix = EtcdContractSupport.keyPrefix()),
        )
}

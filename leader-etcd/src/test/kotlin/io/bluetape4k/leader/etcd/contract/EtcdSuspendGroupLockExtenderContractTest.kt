package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance

/**
 * etcd suspend group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {
    override val elector: SuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(
            EtcdContractSupport.client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = EtcdContractSupport.keyPrefix(),
            ),
        )
}

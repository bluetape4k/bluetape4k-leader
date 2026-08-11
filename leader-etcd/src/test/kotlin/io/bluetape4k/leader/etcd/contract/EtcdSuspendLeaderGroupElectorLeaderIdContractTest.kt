package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElector
import org.junit.jupiter.api.TestInstance

/**
 * etcd suspend group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(
            EtcdContractSupport.client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                keyPrefix = EtcdContractSupport.keyPrefix(),
            ),
        )
}

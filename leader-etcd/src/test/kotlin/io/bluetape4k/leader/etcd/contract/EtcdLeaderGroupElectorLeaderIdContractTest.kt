package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import org.junit.jupiter.api.TestInstance

/**
 * etcd blocking group leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {
    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        EtcdLeaderGroupElector(
            EtcdContractSupport.client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = options,
                keyPrefix = EtcdContractSupport.keyPrefix(),
            ),
        )
}

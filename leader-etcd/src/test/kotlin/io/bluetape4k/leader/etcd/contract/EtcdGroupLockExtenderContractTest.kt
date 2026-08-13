package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import org.junit.jupiter.api.TestInstance

/**
 * etcd blocking group LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {
    override val elector: LeaderGroupElector =
        EtcdLeaderGroupElector(
            EtcdContractSupport.client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = EtcdContractSupport.keyPrefix(),
            ),
        )
}

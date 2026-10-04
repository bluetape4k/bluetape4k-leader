package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * etcd blocking LockExtender contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLockExtenderContractTest: AbstractSyncLockExtenderContractTest() {

    companion object: KLogging()

    override val elector: LeaderElector =
        EtcdLeaderElector(
            EtcdContractSupport.client,
            EtcdLeaderElectionOptions(keyPrefix = EtcdContractSupport.keyPrefix()),
        )
}

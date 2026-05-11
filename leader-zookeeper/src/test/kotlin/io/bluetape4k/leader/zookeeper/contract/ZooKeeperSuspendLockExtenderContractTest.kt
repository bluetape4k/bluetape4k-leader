package io.bluetape4k.leader.zookeeper.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.zookeeper.AbstractZooKeeperLeaderTest
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 ZooKeeper backend 구현 — T13 PR 8 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperSuspendLockExtenderContractTest: AbstractSuspendLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        val server = AbstractZooKeeperLeaderTest.zookeeper
    }

    override val elector: SuspendLeaderElector =
        ZooKeeperSuspendLeaderElector(AbstractZooKeeperLeaderTest.curator)
}

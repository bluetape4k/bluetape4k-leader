package io.bluetape4k.leader.zookeeper.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.zookeeper.AbstractZooKeeperLeaderTest
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 ZooKeeper backend 구현 — T13 PR 8 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperLockExtenderContractTest: AbstractSyncLockExtenderContractTest() {

    companion object: KLogging() {
        val server = AbstractZooKeeperLeaderTest.zookeeper
    }

    override val elector: LeaderElector = ZooKeeperLeaderElector(AbstractZooKeeperLeaderTest.curator)
}

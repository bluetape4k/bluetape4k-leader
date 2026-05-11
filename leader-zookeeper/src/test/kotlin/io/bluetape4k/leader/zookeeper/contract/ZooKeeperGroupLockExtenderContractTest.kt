package io.bluetape4k.leader.zookeeper.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.zookeeper.AbstractZooKeeperLeaderTest
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 ZooKeeper backend 구현 — T13 PR 8 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperGroupLockExtenderContractTest: AbstractGroupLockExtenderContractTest() {

    companion object: KLogging() {
        val server = AbstractZooKeeperLeaderTest.zookeeper
    }

    override val elector: LeaderGroupElector =
        ZooKeeperLeaderGroupElector(
            AbstractZooKeeperLeaderTest.curator,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

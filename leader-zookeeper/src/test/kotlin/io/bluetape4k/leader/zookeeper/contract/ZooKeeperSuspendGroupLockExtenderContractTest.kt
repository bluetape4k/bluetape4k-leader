package io.bluetape4k.leader.zookeeper.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.zookeeper.AbstractZooKeeperLeaderTest
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 ZooKeeper backend 구현 — T13 PR 8 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperSuspendGroupLockExtenderContractTest: AbstractSuspendGroupLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        val server = AbstractZooKeeperLeaderTest.zookeeper
    }

    override val elector: SuspendLeaderGroupElector =
        ZooKeeperSuspendLeaderGroupElector(
            AbstractZooKeeperLeaderTest.curator,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

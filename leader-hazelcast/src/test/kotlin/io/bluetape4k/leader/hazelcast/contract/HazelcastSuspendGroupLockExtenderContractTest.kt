package io.bluetape4k.leader.hazelcast.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.hazelcast.AbstractHazelcastLeaderTest
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 Hazelcast backend 구현 — T12 PR 7 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastSuspendGroupLockExtenderContractTest: AbstractSuspendGroupLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        val server = AbstractHazelcastLeaderTest.hazelcastServer
    }

    override val elector: SuspendLeaderGroupElector =
        HazelcastSuspendLeaderGroupElector(
            AbstractHazelcastLeaderTest.hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

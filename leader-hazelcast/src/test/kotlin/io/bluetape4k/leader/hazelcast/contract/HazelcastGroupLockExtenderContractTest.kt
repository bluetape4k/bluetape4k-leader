package io.bluetape4k.leader.hazelcast.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.hazelcast.AbstractHazelcastLeaderTest
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 Hazelcast backend 구현 — T12 PR 7 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastGroupLockExtenderContractTest: AbstractGroupLockExtenderContractTest() {

    companion object: KLogging() {
        val server = AbstractHazelcastLeaderTest.hazelcastServer
    }

    override val elector: LeaderGroupElector =
        HazelcastLeaderGroupElector(
            AbstractHazelcastLeaderTest.hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

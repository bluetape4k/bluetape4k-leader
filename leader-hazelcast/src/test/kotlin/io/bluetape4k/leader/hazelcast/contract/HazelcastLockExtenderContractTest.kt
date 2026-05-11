package io.bluetape4k.leader.hazelcast.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.hazelcast.AbstractHazelcastLeaderTest
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 Hazelcast backend 구현 — T12 PR 7 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastLockExtenderContractTest: AbstractSyncLockExtenderContractTest() {

    companion object: KLogging() {
        val server = AbstractHazelcastLeaderTest.hazelcastServer
    }

    override val elector: LeaderElector = HazelcastLeaderElector(AbstractHazelcastLeaderTest.hazelcastClient)
}

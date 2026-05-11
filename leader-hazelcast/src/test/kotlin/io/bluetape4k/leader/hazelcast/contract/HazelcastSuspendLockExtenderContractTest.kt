package io.bluetape4k.leader.hazelcast.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.hazelcast.AbstractHazelcastLeaderTest
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 Hazelcast backend 구현 — T12 PR 7 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastSuspendLockExtenderContractTest: AbstractSuspendLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        val server = AbstractHazelcastLeaderTest.hazelcastServer
    }

    override val elector: SuspendLeaderElector =
        HazelcastSuspendLeaderElector(AbstractHazelcastLeaderTest.hazelcastClient)
}

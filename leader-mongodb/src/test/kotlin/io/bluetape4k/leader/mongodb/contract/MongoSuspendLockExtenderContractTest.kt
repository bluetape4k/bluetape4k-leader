package io.bluetape4k.leader.mongodb.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 MongoDB backend 구현 — T9 PR 4 (Issue #79).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {

    companion object : KLoggingChannel() {
        val mongo = AbstractMongoLeaderTest.mongoServer
    }

    override val elector: SuspendLeaderElector = runBlocking {
        MongoSuspendLeaderElector(AbstractMongoLeaderTest.coroutineLockCollection)
    }
}

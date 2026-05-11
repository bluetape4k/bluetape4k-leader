package io.bluetape4k.leader.mongodb.contract

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 MongoDB backend 구현 — T9 PR 4 (Issue #79).
 *
 * Testcontainers `bluetape4k-testcontainers` 의 [io.bluetape4k.testcontainers.storage.MongoDBServer.Launcher.mongoDB]
 * singleton 을 사용 — JVM 당 1회만 spin-up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {

    companion object : KLogging() {
        val mongo = AbstractMongoLeaderTest.mongoServer
    }

    override val elector: LeaderElector =
        MongoLeaderElector(AbstractMongoLeaderTest.lockCollection)
}

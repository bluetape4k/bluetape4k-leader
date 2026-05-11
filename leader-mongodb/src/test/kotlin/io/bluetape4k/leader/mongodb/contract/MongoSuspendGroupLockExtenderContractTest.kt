package io.bluetape4k.leader.mongodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 MongoDB backend 구현 — T9 PR 4 (Issue #79).
 *
 * `maxLeaders = 2` 로 기본 설정.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {

    companion object : KLoggingChannel() {
        val mongo = AbstractMongoLeaderTest.mongoServer
    }

    override val elector: SuspendLeaderGroupElector = runBlocking {
        MongoSuspendLeaderGroupElector(
            groupCollection = AbstractMongoLeaderTest.groupLockCollection,
            coroutineGroupCollection = AbstractMongoLeaderTest.coroutineGroupLockCollection,
            options = MongoLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
            ),
        )
    }
}

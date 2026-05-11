package io.bluetape4k.leader.mongodb.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 MongoDB backend 구현 — T9 PR 4 (Issue #79).
 *
 * `maxLeaders = 2` 로 기본 설정. per-slot [io.bluetape4k.leader.mongodb.lock.MongoLock] 의
 * R6 filter (`expireAt > now`) 적용된 extendDetailed 사용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {

    companion object : KLogging() {
        val mongo = AbstractMongoLeaderTest.mongoServer
    }

    override val elector: LeaderGroupElector =
        MongoLeaderGroupElector(
            AbstractMongoLeaderTest.groupLockCollection,
            MongoLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                retryDelay = MongoLeaderElectionOptions.Default.retryDelay,
            ),
        )
}

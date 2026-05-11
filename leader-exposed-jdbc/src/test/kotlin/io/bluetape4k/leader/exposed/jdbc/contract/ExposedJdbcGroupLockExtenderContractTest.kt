package io.bluetape4k.leader.exposed.jdbc.contract

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 Exposed JDBC backend 구현 — T10 PR 5 (Issue #79).
 *
 * `maxLeaders = 2` 로 기본 설정. per-slot row 의 R6 guard (`locked_until > now`) 적용된 extendDetailed 사용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedJdbcGroupLockExtenderContractTest: AbstractGroupLockExtenderContractTest() {

    companion object: KLogging() {
        private val db: Database by lazy {
            val testDb = TestDB.H2
            val connection = testDb.db ?: testDb.connect()
            ExposedJdbcSchemaInitializer.ensureSchema(connection)
            connection
        }
    }

    override val elector: LeaderGroupElector =
        ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
            ),
        )
}

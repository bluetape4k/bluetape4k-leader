package io.bluetape4k.leader.exposed.jdbc.contract

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 Exposed JDBC backend 구현 — T10 PR 5 (Issue #79).
 *
 * H2 in-memory DB 를 기본으로 사용 — Testcontainers 없이 빠르게 실행됩니다.
 * 모든 DB 방언에 대한 검증은 기존 [io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionTest]
 * 가 parameterized test 로 담당합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedJdbcLockExtenderContractTest: AbstractSyncLockExtenderContractTest() {

    companion object: KLogging() {
        private val db: Database by lazy {
            val testDb = TestDB.H2
            val connection = testDb.db ?: testDb.connect()
            ExposedJdbcSchemaInitializer.ensureSchema(connection)
            connection
        }
    }

    override val elector: LeaderElector = ExposedJdbcLeaderElector(db)
}

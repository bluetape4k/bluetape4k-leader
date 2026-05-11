package io.bluetape4k.leader.exposed.r2dbc.contract

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 Exposed R2DBC backend 구현 — T11 PR 6 (Issue #79).
 *
 * H2 in-memory R2DBC 를 사용 — 빠른 CI 실행. Multi-dialect 검증은 기존
 * [io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElectionTest] parameterized 테스트가 담당.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedR2dbcSuspendLockExtenderContractTest: AbstractSuspendLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        private val db: R2dbcDatabase by lazy {
            val database = R2dbcDatabase.connect(
                "r2dbc:h2:mem:///leader_contract_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                user = "",
                password = "",
            )
            runSuspendIO { ExposedR2dbcSchemaInitializer.ensureSchema(database) }
            database
        }
    }

    override val elector: SuspendLeaderElector = runBlocking {
        ExposedR2DbcSuspendLeaderElector(db)
    }
}

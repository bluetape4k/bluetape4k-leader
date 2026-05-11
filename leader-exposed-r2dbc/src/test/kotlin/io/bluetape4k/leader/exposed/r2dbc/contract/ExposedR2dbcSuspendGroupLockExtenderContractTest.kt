package io.bluetape4k.leader.exposed.r2dbc.contract

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 Exposed R2DBC backend 구현 — T11 PR 6 (Issue #79).
 *
 * `maxLeaders = 2`. per-slot row 의 R6 guard (`locked_until > now`) 적용된 suspend extendDetailed 사용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedR2dbcSuspendGroupLockExtenderContractTest: AbstractSuspendGroupLockExtenderContractTest() {

    companion object: KLoggingChannel() {
        private val db: R2dbcDatabase by lazy {
            val database = R2dbcDatabase.connect(
                "r2dbc:h2:mem:///leader_group_contract_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                user = "",
                password = "",
            )
            runSuspendIO { ExposedR2dbcSchemaInitializer.ensureSchema(database) }
            database
        }
    }

    override val elector: SuspendLeaderGroupElector = runBlocking {
        ExposedR2DbcSuspendLeaderGroupElector(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
            ),
        )
    }
}

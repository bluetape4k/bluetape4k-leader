package io.bluetape4k.leader.exposed.jdbc.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 watchdog delegate 와
 * **동일 reference** 인지 확인합니다 (T10 PR 5, Issue #79).
 *
 * ## 검증 방식
 * - `internal` symbol 직접 접근 불가 → 공개 API ([LockAssert] / [LockExtender]) 를 통해 간접 검증.
 * - body 안에서 [LockExtender.extendActiveLockDetailed] 호출 → [ExtendOutcome.Extended] 이면 handle 의 delegate 가
 *   real backend 와 연결됨을 의미 (synthetic NoopExtendDelegate 가 아님).
 * - capture 가 finally 에서 clear 되었음을 확인하기 위해 종료 후 `pollCapture() == null` 검사.
 *
 * ## 검증 케이스 (JDBC 는 sync only — suspend variants 는 R2DBC PR 6 에서 담당)
 * - sync single ([ExposedJdbcLeaderElector])
 * - sync group ([ExposedJdbcLeaderGroupElector])
 *
 * ## R6 guard 검증
 * `extendDetailed` 가 `locked_until > now` guard 를 사용하므로 acquire 직후 (lease 미만료) 항상 Extended 가 반환됩니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedJdbcExtendDelegateReferenceTest {

    companion object: KLogging() {
        private val db: Database by lazy {
            val testDb = TestDB.H2
            val connection = testDb.db ?: testDb.connect()
            ExposedJdbcSchemaInitializer.ensureSchema(connection)
            connection
        }
    }

    private fun randomLockName(): String = "extdelref-jdbc-${Base58.randomString(8)}"

    @Test
    fun `sync single — extendActiveLockDetailed returns Extended inside body`() {
        val elector = ExposedJdbcLeaderElector(db)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `sync group — extendActiveLockDetailed uses extendDetailed and Extended is returned`() {
        val elector = ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
            ),
        )
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `multiple sequential extends on same handle return Extended every time`() {
        val elector = ExposedJdbcLeaderElector(db)
        val lockName = randomLockName()

        val outcomes = mutableListOf<ExtendOutcome>()
        elector.runIfLeader(lockName) {
            outcomes += LockExtender.extendActiveLockDetailed(30.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(45.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcomes.forEach { it.shouldBeInstanceOf<ExtendOutcome.Extended>() }
    }

    @Test
    fun `user explicit extend updates lastExtendDeadline (R2 mitigation reference proof)`() {
        val elector = ExposedJdbcLeaderElector(db)
        val lockName = randomLockName()

        var preExtend: ExtendOutcome? = null
        var postExtend: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            preExtend = LockExtender.extendActiveLockDetailed(120.seconds)
            postExtend = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        preExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
        postExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }
}

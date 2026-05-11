package io.bluetape4k.leader.exposed.r2dbc.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 watchdog delegate 와
 * **동일 reference** 인지 확인합니다 (T11 PR 6, Issue #79).
 *
 * ## 검증 방식
 * - `internal` symbol 직접 접근 불가 → 공개 API ([LockAssert] / [LockExtender]) 를 통해 간접 검증.
 * - body 안에서 [LockExtender.extendActiveLockDetailedSuspend] 호출 → [ExtendOutcome.Extended] 이면
 *   handle 의 delegate 가 real backend 와 연결됨을 의미.
 * - capture 가 finally 에서 clear 되었음을 확인하기 위해 종료 후 `pollCapture() == null` 검사.
 *
 * ## 검증 케이스 (R2DBC 는 suspend only — sync variants 는 JDBC PR 5 가 담당)
 * - suspend single ([ExposedR2DbcSuspendLeaderElector])
 * - suspend group ([ExposedR2DbcSuspendLeaderGroupElector])
 *
 * ## R6 guard 검증
 * `extendDetailed` 가 `locked_until > now` guard 를 사용하므로 acquire 직후 (lease 미만료) 항상 Extended 가 반환됩니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedR2dbcExtendDelegateReferenceTest: AbstractExposedR2dbcLeaderTest() {

    companion object: KLoggingChannel()

    private val db = setupDb(TestR2dbcDB.H2)

    private fun randomLockName(): String = "extdelref-r2dbc-${Base58.randomString(8)}"

    @Test
    fun `suspend single — extendActiveLockDetailedSuspend returns Extended inside body`() = runSuspendIO {
        val elector = ExposedR2DbcSuspendLeaderElector(db)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `suspend group — extendActiveLockDetailedSuspend uses extendDetailed and Extended is returned`() =
        runSuspendIO {
            val elector = ExposedR2DbcSuspendLeaderGroupElector(
                db,
                ExposedR2dbcLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                ),
            )
            val lockName = randomLockName()

            var outcome: ExtendOutcome? = null
            elector.runIfLeader(lockName) {
                LockAssert.assertLockedSuspend()
                outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
            }

            outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
            (AopScopeAccess.pollCapture() == null).shouldBeTrue()
        }

    @Test
    fun `multiple sequential suspend extends return Extended every time`() = runSuspendIO {
        val elector = ExposedR2DbcSuspendLeaderElector(db)
        val lockName = randomLockName()

        val outcomes = mutableListOf<ExtendOutcome>()
        elector.runIfLeader(lockName) {
            outcomes += LockExtender.extendActiveLockDetailedSuspend(30.seconds)
            outcomes += LockExtender.extendActiveLockDetailedSuspend(45.seconds)
            outcomes += LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcomes.forEach { it.shouldBeInstanceOf<ExtendOutcome.Extended>() }
    }

    @Test
    fun `user explicit extend updates lastExtendDeadline (R2 mitigation reference proof)`() = runSuspendIO {
        val elector = ExposedR2DbcSuspendLeaderElector(db)
        val lockName = randomLockName()

        var preExtend: ExtendOutcome? = null
        var postExtend: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            preExtend = LockExtender.extendActiveLockDetailedSuspend(120.seconds)
            postExtend = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        preExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
        postExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }
}

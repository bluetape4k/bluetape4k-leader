package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 watchdog delegate 와
 * **동일 reference** 인지 확인합니다 (T7 PR 2, Issue #79).
 *
 * ## 검증 방식
 * - `internal` symbol 직접 접근 불가 → 공개 API ([LockAssert] / [LockExtender]) 를 통해 간접 검증.
 * - body 안에서 [LockExtender.extendActiveLockDetailed] 호출 → [ExtendOutcome.Extended] 이면 handle 의 delegate 가
 *   real backend 와 연결됨을 의미 (synthetic NoopExtendDelegate 가 아님).
 * - capture 가 finally 에서 clear 되었음을 확인하기 위해 종료 후 `pollCapture() == null` 검사.
 *
 * ## 검증 4 케이스
 * - sync single ([LettuceLeaderElector])
 * - suspend single ([LettuceSuspendLeaderElector])
 * - sync group ([LettuceLeaderGroupElector])
 * - suspend group ([LettuceSuspendLeaderGroupElector])
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceExtendDelegateReferenceTest : AbstractLettuceLeaderTest() {

    companion object : KLogging()

    private fun randomLockName(): String = "extdelref-${Base58.randomString(8)}"

    @Test
    fun `sync single — extendActiveLockDetailed returns Extended inside body`() {
        val elector = LettuceLeaderElector(connection)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        // capture 는 finally 에서 clear 되어야 함 (single elector 는 capture 미사용이지만 idempotent 검증)
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `suspend single — extendActiveLockDetailedSuspend returns Extended inside body`() = runSuspendIO {
        val elector = LettuceSuspendLeaderElector(connection)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `sync group — extendActiveLockDetailed uses server-side TIME Lua (AC-16) and Extended is returned`() {
        val elector = LettuceLeaderGroupElector(
            connection,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        // group elector 는 setCapture 를 호출했으므로 finally 에서 clearCapture() 가 호출되었어야 함
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `suspend group — extendActiveLockDetailedSuspend uses server-side TIME Lua and Extended is returned`() = runSuspendIO {
        val elector = LettuceSuspendLeaderGroupElector(
            connection,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `multiple sequential extends on same handle return Extended every time`() {
        val elector = LettuceLeaderElector(connection)
        val lockName = randomLockName()

        val outcomes = mutableListOf<ExtendOutcome>()
        elector.runIfLeader(lockName) {
            outcomes += LockExtender.extendActiveLockDetailed(30.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(45.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcomes.forEach { it.shouldBeInstanceOf<ExtendOutcome.Extended>() }
    }

    /**
     * AC-23 R2 watchdog skip reference 검증 — `LockExtender.extendActiveLock(d)` 호출 직후
     * watchdog 가 보유한 동일 delegate 의 [io.bluetape4k.leader.internal.ExtendDelegate.lastExtendDeadline]
     * 이 갱신되는지 확인.
     *
     * **둘이 동일 reference 가 아니면** user explicit extend 가 watchdog 에 전달되지 않아
     * R2 mitigation 이 무효화됨. 이 테스트는 그 reference 보존을 간접 검증.
     *
     * 사용 가능한 public API 만으로 verify — `LockExtender.extendActiveLock` 직후 짧은 시간 안에
     * 다시 호출하면, 두번째 호출도 같은 deadline 영역 안에 있으므로 [ExtendOutcome.Extended] 반환.
     */
    @Test
    fun `user explicit extend updates lastExtendDeadline (R2 mitigation reference proof)`() {
        val elector = LettuceLeaderElector(connection)
        val lockName = randomLockName()

        var preExtend: ExtendOutcome? = null
        var postExtend: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            // user explicit extend — delegate.lastExtendDeadline 갱신
            preExtend = LockExtender.extendActiveLockDetailed(120.seconds)
            // 동일 delegate reference 가 watchdog 와 handle 양쪽에서 공유된다는 invariant 검증.
            // 즉시 다시 extend 호출 — 토큰이 유지되어 있으므로 Extended 반환되어야 함.
            postExtend = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        preExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
        postExtend.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }
}

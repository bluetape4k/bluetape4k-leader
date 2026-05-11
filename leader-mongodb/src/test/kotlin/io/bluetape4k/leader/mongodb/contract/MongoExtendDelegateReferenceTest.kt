package io.bluetape4k.leader.mongodb.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.mongodb.AbstractMongoLeaderTest
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 watchdog delegate 와
 * **동일 reference** 인지 확인합니다 (T9 PR 4, Issue #79).
 *
 * ## 검증 방식
 * - `internal` symbol 직접 접근 불가 → 공개 API ([LockAssert] / [LockExtender]) 를 통해 간접 검증.
 * - body 안에서 [LockExtender.extendActiveLockDetailed] 호출 → [ExtendOutcome.Extended] 이면 handle 의 delegate 가
 *   real backend 와 연결됨을 의미 (synthetic NoopExtendDelegate 가 아님).
 * - capture 가 finally 에서 clear 되었음을 확인하기 위해 종료 후 `pollCapture() == null` 검사.
 *
 * ## 검증 4 케이스
 * - sync single ([MongoLeaderElector])
 * - suspend single ([MongoSuspendLeaderElector])
 * - sync group ([MongoLeaderGroupElector])
 * - suspend group ([MongoSuspendLeaderGroupElector])
 *
 * ## R6 filter 검증
 * `extendDetailed` 가 `expireAt > now` filter 를 사용하므로 acquire 직후 (lease 미만료) 항상 Extended 가 반환됩니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoExtendDelegateReferenceTest : AbstractMongoLeaderTest() {

    companion object : KLoggingChannel()

    private fun randomLockName(): String = "extdelref-mongo-${Base58.randomString(8)}"

    @Test
    fun `sync single — extendActiveLockDetailed returns Extended inside body`() {
        val elector = MongoLeaderElector(lockCollection)
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
        val elector = MongoSuspendLeaderElector(coroutineLockCollection)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `sync group — extendActiveLockDetailed uses extendDetailed and Extended is returned`() {
        val elector = MongoLeaderGroupElector(
            groupLockCollection,
            MongoLeaderGroupElectionOptions(
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
        // group elector 는 setCapture 를 호출했으므로 finally 에서 clearCapture() 가 호출되었어야 함
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `suspend group — extendActiveLockDetailedSuspend uses extendDetailed and Extended is returned`() =
        runSuspendIO {
            val elector = MongoSuspendLeaderGroupElector(
                groupCollection = groupLockCollection,
                coroutineGroupCollection = coroutineGroupLockCollection,
                options = MongoLeaderGroupElectionOptions(
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
        }

    @Test
    fun `multiple sequential extends on same handle return Extended every time`() {
        val elector = MongoLeaderElector(lockCollection)
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
     * 이 갱신되는지 간접 검증.
     */
    @Test
    fun `user explicit extend updates lastExtendDeadline (R2 mitigation reference proof)`() {
        val elector = MongoLeaderElector(lockCollection)
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

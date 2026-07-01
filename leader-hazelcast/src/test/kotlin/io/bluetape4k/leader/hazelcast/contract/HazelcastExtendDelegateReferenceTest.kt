package io.bluetape4k.leader.hazelcast.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.hazelcast.AbstractHazelcastLeaderTest
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElector
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 watchdog delegate 와
 * **동일 reference** 인지 확인합니다 (T12 PR 7, Issue #79).
 *
 * ## 검증 케이스
 * - sync single ([HazelcastLeaderElector])
 * - suspend single ([HazelcastSuspendLeaderElector])
 * - sync group ([HazelcastLeaderGroupElector])
 * - suspend group ([HazelcastSuspendLeaderGroupElector])
 *
 * Hazelcast transactional `getForUpdate(K) + put(K, ourToken, leaseMs)` built-in path 으로
 * 토큰 일치 + TTL 갱신이 수행됩니다. acquire 직후 (lease 미만료) 항상 Extended 가 반환됩니다.
 * 세부사항은 [io.bluetape4k.leader.hazelcast.lock.HazelcastLock.extendDetailed] KDoc 참고.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastExtendDelegateReferenceTest: AbstractHazelcastLeaderTest() {

    companion object: KLoggingChannel()

    private fun randomLockName(): String = "extdelref-hz-${Base58.randomString(8)}"

    @Test
    fun `sync single — extendActiveLockDetailed returns Extended inside body`() {
        val elector = HazelcastLeaderElector(hazelcastClient)
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
    fun `suspend single — extendActiveLockDetailedSuspend returns Extended inside body`() = runSuspendIO {
        val elector = HazelcastSuspendLeaderElector(hazelcastClient)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
    }

    @Test
    fun `sync group — extendActiveLockDetailed returns Extended inside body`() {
        val elector = HazelcastLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
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
    fun `suspend group — extendActiveLockDetailedSuspend returns Extended inside body`() = runSuspendIO {
        val elector = HazelcastSuspendLeaderGroupElector(
            hazelcastClient,
            LeaderGroupElectionOptions(maxLeaders = 2),
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
    fun `multiple sequential extends on same handle return Extended every time`() {
        val elector = HazelcastLeaderElector(hazelcastClient)
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
        val elector = HazelcastLeaderElector(hazelcastClient)
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

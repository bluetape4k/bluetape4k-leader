package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.internal.LockStateHolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LocalLeaderElector] capture integration test.
 *
 * `runIfLeader` 안에서 [LockStateHolder] / [LockAssert] / [LockExtender] 가 정상 동작하는지 검증.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("NonAsciiCharacters")
class LocalLeaderElectorCaptureTest {

    private val election = LocalLeaderElector()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── LockStateHolder 통합 ───────────────────────────────────────────────

    @Test
    fun `runIfLeader 안에서 LockStateHolder peekSync 가 Real handle 을 반환한다`() {
        val lockName = randomLockName()
        var capturedHandle: LeaderLockHandle? = null

        election.runIfLeader(lockName) {
            capturedHandle = LockStateHolder.peekSync()
        }

        capturedHandle.shouldNotBeNull()
        capturedHandle.shouldBeInstanceOf<LeaderLockHandle.Real>()
        (capturedHandle as LeaderLockHandle.Real).lockName shouldBeEqualTo lockName
    }

    @Test
    fun `runIfLeader 완료 후 LockStateHolder 가 비워진다`() {
        val lockName = randomLockName()

        election.runIfLeader(lockName) { /* action */ }

        LockStateHolder.peekSync().shouldBeNull()
    }

    @Test
    fun `runIfLeader 안에서 LockAssert assertLocked 가 성공한다`() {
        val lockName = randomLockName()
        var assertionPassed = false

        election.runIfLeader(lockName) {
            LockAssert.assertLocked()
            LockAssert.assertLocked(lockName)
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 안에서 LockAssert isLocked 가 true 를 반환한다`() {
        val lockName = randomLockName()
        var isLocked = false

        election.runIfLeader(lockName) {
            isLocked = LockAssert.isLocked()
        }

        isLocked.shouldBeTrue()
    }

    // ── LockExtender 통합 ─────────────────────────────────────────────────

    @Test
    fun `runIfLeader 안에서 LockExtender extendActiveLock 이 true 를 반환한다`() {
        val lockName = randomLockName()
        var extended = false

        election.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock(30.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 안에서 LockExtender extendActiveLock lockName 지정 버전이 true 를 반환한다`() {
        val lockName = randomLockName()
        var extended = false

        election.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock(lockName, 30.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 완료 후 LockExtender extendActiveLock 이 false 를 반환한다`() {
        val lockName = randomLockName()

        election.runIfLeader(lockName) { /* action */ }

        // scope 밖에서 호출 → NotHeld → false
        val extended = LockExtender.extendActiveLock(30.seconds)
        extended shouldBeEqualTo false
    }

    // ── 재진입 동작 ───────────────────────────────────────────────────────

    @Test
    fun `runIfLeader 안에서 동일 lockName 으로 재진입 시 inner 에서도 LockAssert 가 통과한다`() {
        val lockName = randomLockName()
        var innerLocked = false

        election.runIfLeader(lockName) {
            election.runIfLeader(lockName) {
                LockAssert.assertLocked()
                innerLocked = true
            }
        }

        innerLocked.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 재진입 시 inner handle 의 reentryDepth 가 1 이다`() {
        val lockName = randomLockName()
        var outerDepth = -1
        var innerDepth = -1

        election.runIfLeader(lockName) {
            outerDepth = (LockStateHolder.peekSync() as? LeaderLockHandle.Real)?.reentryDepth ?: -1
            election.runIfLeader(lockName) {
                innerDepth = (LockStateHolder.peekSync() as? LeaderLockHandle.Real)?.reentryDepth ?: -1
            }
        }

        outerDepth shouldBeEqualTo 0
        innerDepth shouldBeEqualTo 1
    }

    @Test
    fun `재진입 inner 에서 LockExtender extend 가 outer 의 delegate 를 갱신한다`() {
        val lockName = randomLockName()
        var innerExtended = false

        election.runIfLeader(lockName) {
            election.runIfLeader(lockName) {
                // inner scope 에서 extend 호출 → outer extendDelegate 가 갱신됨
                innerExtended = LockExtender.extendActiveLock(60.seconds)
            }
        }

        innerExtended.shouldBeTrue()
    }

    // ── action 예외 시 cleanup ────────────────────────────────────────────

    @Test
    fun `action 예외 후에도 LockStateHolder 가 비워진다`() {
        val lockName = randomLockName()

        runCatching {
            election.runIfLeader(lockName) {
                throw RuntimeException("intentional")
            }
        }

        LockStateHolder.peekSync().shouldBeNull()
    }

    // ── autoExtend + LockExtender R2 미구현 검증 ──────────────────────────

    @Test
    fun `autoExtend=true 로 runIfLeader 실행 후 watchdog 이 state leaseUntil 을 갱신한다`() {
        val stateElection = LocalLeaderElector(
            LeaderElectionOptions(leaseTime = 150.milliseconds, autoExtend = true)
        )
        val lockName = randomLockName()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        val holder = Thread {
            stateElection.runIfLeader(lockName) {
                started.countDown()
                release.await()
            }
        }.apply { start() }

        started.await()
        val initial = stateElection.state(lockName).leader?.leaseUntil
        Thread.sleep(250)
        val extended = stateElection.state(lockName).leader?.leaseUntil

        release.countDown()
        holder.join()

        initial.shouldNotBeNull()
        extended.shouldNotBeNull()
        extended.isAfter(initial).shouldBeTrue()
    }
}

package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LocalSuspendLeaderElector] capture integration test.
 *
 * `runIfLeader` 안에서 [LockHandleElement] / [LockAssert] / [LockExtender] suspend 변형이
 * 정상 동작하는지 검증.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("NonAsciiCharacters")
class LocalSuspendLeaderElectorCaptureTest {

    private val election = LocalSuspendLeaderElector()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── LockHandleElement (CoroutineContext) 통합 ─────────────────────────

    @Test
    fun `runIfLeader 안에서 coroutineContext LockHandleElement 가 Real handle 을 담고 있다`() = runSuspendIO {
        val lockName = randomLockName()
        var lockName2: String? = null
        var isReal = false

        election.runIfLeader(lockName) {
            // LockAssert 를 통해 간접 검증 (LockHandleElement 내부 handle 이 Real 임을 보장)
            LockAssert.assertLockedSuspend()
            LockAssert.assertLockedSuspend(lockName)
            lockName2 = lockName  // assertLockedSuspend 통과 = Real handle 존재 증거
            isReal = LockAssert.isLockedSuspend(lockName)
        }

        // assertLockedSuspend 가 throw 없이 통과했으면 Real handle 이 존재한다는 증거
        lockName2 shouldBeEqualTo lockName
        isReal.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 완료 후 coroutineContext 에 LockHandleElement 가 없다`() = runSuspendIO {
        val lockName = randomLockName()

        election.runIfLeader(lockName) { /* action */ }

        // scope 밖 — element 없음
        coroutineContext[LockHandleElement].shouldBeNull()
    }

    // ── LockAssert suspend 변형 ───────────────────────────────────────────

    @Test
    fun `runIfLeader 안에서 LockAssert assertLockedSuspend 가 성공한다`() = runSuspendIO {
        val lockName = randomLockName()
        var assertionPassed = false

        election.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            LockAssert.assertLockedSuspend(lockName)
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 안에서 LockAssert isLockedSuspend 가 true 를 반환한다`() = runSuspendIO {
        val lockName = randomLockName()
        var isLocked = false

        election.runIfLeader(lockName) {
            isLocked = LockAssert.isLockedSuspend()
        }

        isLocked.shouldBeTrue()
    }

    // ── LockExtender suspend 변형 ─────────────────────────────────────────

    @Test
    fun `runIfLeader 안에서 LockExtender extendActiveLockSuspend 가 true 를 반환한다`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = false

        election.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend(30.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 안에서 LockExtender extendActiveLockSuspend lockName 지정 버전이 true 를 반환한다`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = false

        election.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend(lockName, 30.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `runIfLeader 완료 후 LockExtender extendActiveLockSuspend 가 false 를 반환한다`() = runSuspendIO {
        val lockName = randomLockName()

        election.runIfLeader(lockName) { /* action */ }

        // scope 밖 → NotHeld → false
        val extended = LockExtender.extendActiveLockSuspend(30.seconds)
        extended shouldBeEqualTo false
    }

    // ── action 예외 시 cleanup ────────────────────────────────────────────

    @Test
    fun `action 예외 후에도 coroutineContext 에서 LockHandleElement 가 사라진다`() = runSuspendIO {
        val lockName = randomLockName()

        runCatching {
            election.runIfLeader(lockName) {
                throw RuntimeException("intentional")
            }
        }

        coroutineContext[LockHandleElement].shouldBeNull()
    }

    // ── autoExtend + delegate 통합 ────────────────────────────────────────

    @Test
    fun `autoExtend=true 로 runIfLeader 실행 후 watchdog 이 state leaseUntil 을 갱신한다`() = runSuspendIO {
        val stateElection = LocalSuspendLeaderElector(
            LeaderElectionOptions(leaseTime = 150.milliseconds, autoExtend = true)
        )
        val lockName = randomLockName()

        var initialLeaseUntil: java.time.Instant? = null
        var extendedLeaseUntil: java.time.Instant? = null

        val holderReady = Channel<Unit>(1)
        val holderRelease = Channel<Unit>(1)

        coroutineScope {
            val holder = async {
                stateElection.runIfLeader(lockName) {
                    initialLeaseUntil = stateElection.state(lockName).leader?.leaseUntil
                    holderReady.send(Unit)
                    holderRelease.receive()
                    extendedLeaseUntil = stateElection.state(lockName).leader?.leaseUntil
                }
            }

            holderReady.receive()
            delay(250.milliseconds)
            holderRelease.send(Unit)
            holder.await()
        }

        initialLeaseUntil.shouldNotBeNull()
        extendedLeaseUntil.shouldNotBeNull()
        extendedLeaseUntil!!.isAfter(initialLeaseUntil!!).shouldBeTrue()
    }

    // ── delay 안에서도 LockHandleElement 유지 ────────────────────────────

    @Test
    fun `suspend delay 후에도 LockHandleElement 가 유지된다`() = runSuspendIO {
        val lockName = randomLockName()
        var lockedBeforeDelay = false
        var lockedAfterDelay = false

        election.runIfLeader(lockName) {
            lockedBeforeDelay = LockAssert.isLockedSuspend(lockName)
            delay(20.milliseconds)
            lockedAfterDelay = LockAssert.isLockedSuspend(lockName)
        }

        lockedBeforeDelay.shouldBeTrue()
        lockedAfterDelay.shouldBeTrue()
    }
}

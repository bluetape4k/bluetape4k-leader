package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * Backend-agnostic 한 LockAssert/LockExtender × sync [LeaderElector] contract.
 *
 * 각 backend 의 contract test 가 이 abstract class 를 상속하여 [elector] 를 제공한다.
 *
 * ## 검증 계약 (AC-1 / Issue #79)
 *
 * 1. `assertLocked()` — [LeaderElector.runIfLeader] 본문 안에서는 pass, 밖에서는 [IllegalStateException]
 * 2. `assertLocked(lockName)` — 이름 일치 시 pass, 불일치 시 [IllegalStateException]
 * 3. `isLocked()` — 본문 안 `true`, 밖 `false`
 * 4. `extendActiveLock(d)` — 본문 안 `true`, 완료 후 `false`
 * 5. `extendActiveLockDetailed(d)` — [ExtendOutcome.Extended] 반환
 * 6. `extendActiveLock(lockName, d)` — 이름 일치 `true`, 불일치 `false`
 *
 * ## 사용 방법
 *
 * ```kotlin
 * class RedissonLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {
 *     companion object : KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override val elector: LeaderElector = RedissonLeaderElector(client)
 * }
 * ```
 *
 * ## 범위 한계
 *
 * - Group elector ([io.bluetape4k.leader.LeaderGroupElector]) 계약은 별도 base 로 위임.
 *   (T5 기준 group elector 는 handle push 를 구현하지 않으므로 별도 tracking.)
 * - suspend 계약은 [AbstractSuspendLockExtenderContractTest] 가 담당.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSyncLockExtenderContractTest {

    /** 각 backend 가 자기 [LeaderElector] 인스턴스를 제공한다. */
    protected abstract val elector: LeaderElector

    private fun randomLockName(): String = "ctr-s-${Base58.randomString(8)}"

    // ── assertLocked() ────────────────────────────────────────────────────

    @Test
    fun `assertLocked passes inside runIfLeader body`() {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()          // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLocked throws outside runIfLeader body`() {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLocked()
        }
    }

    @Test
    fun `assertLocked with matching lockName passes inside body`() {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked(lockName)  // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLocked with mismatched lockName throws inside body`() {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLocked("wrong-lock-name")
            }
        }
    }

    // ── isLocked() ───────────────────────────────────────────────────────

    @Test
    fun `isLocked returns true inside runIfLeader body and false outside`() {
        val lockName = randomLockName()

        LockAssert.isLocked().shouldBeFalse()

        elector.runIfLeader(lockName) {
            LockAssert.isLocked().shouldBeTrue()
            LockAssert.isLocked(lockName).shouldBeTrue()
        }

        LockAssert.isLocked().shouldBeFalse()
    }

    @Test
    fun `isLocked with mismatched lockName returns false inside body`() {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) {
            LockAssert.isLocked("other-lock").shouldBeFalse()
        }
    }

    // ── extendActiveLock() ────────────────────────────────────────────────

    @Test
    fun `extendActiveLock returns true when lock is held`() {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock(60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLock returns false after runIfLeader body completes`() {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) { /* acquire and release */ }

        // scope ended — no active handle
        LockExtender.extendActiveLock(60.seconds).shouldBeFalse()
    }

    @Test
    fun `extendActiveLock with matching lockName returns true`() {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock(lockName, 60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLock with mismatched lockName returns false`() {
        val lockName = randomLockName()
        var extended = true  // intentionally start as true to verify it becomes false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock("wrong-lock-name", 60.seconds)
        }

        extended.shouldBeFalse()
    }

    // ── extendActiveLockDetailed() ─────────────────────────────────────────

    @Test
    fun `extendActiveLockDetailed returns Extended when lock is held`() {
        val lockName = randomLockName()
        var outcome: ExtendOutcome? = null

        elector.runIfLeader(lockName) {
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        (outcome is ExtendOutcome.Extended).shouldBeTrue()
    }

    @Test
    fun `extendActiveLockDetailed returns NotHeld outside runIfLeader body`() {
        val outcome = LockExtender.extendActiveLockDetailed(60.seconds)

        (outcome is ExtendOutcome.NotHeld).shouldBeTrue()
    }

    // ── return value ──────────────────────────────────────────────────────

    @Test
    fun `runIfLeader returns action return value when lock is acquired`() {
        val lockName = randomLockName()
        val result = elector.runIfLeader(lockName) { "contract-ok" }
        result shouldBeEqualTo "contract-ok"
    }
}

package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElector
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
 * Backend-agnostic 한 LockAssert/LockExtender × sync [LeaderGroupElector] contract.
 *
 * 각 backend 의 contract test 가 이 abstract class 를 상속하여 [elector] 를 제공한다.
 *
 * ## 검증 계약 (AC-1 / Issue #79)
 *
 * 1. `assertLocked()` — [LeaderGroupElector.runIfLeader] 본문 안에서는 pass, 밖에서는 [IllegalStateException]
 * 2. `isLocked()` — 본문 안 `true`, 밖 `false`
 * 3. `extendActiveLock(d)` — 본문 안 `true`, 완료 후 `false`
 * 4. `extendActiveLockDetailed(d)` — [ExtendOutcome.Extended] 반환
 *
 * ## 사용 방법
 *
 * ```kotlin
 * class RedissonGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {
 *     companion object : KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override val elector: LeaderGroupElector = RedissonLeaderGroupElector(client)
 * }
 * ```
 *
 * ## 범위 한계
 *
 * - Single elector 계약은 [AbstractSyncLockExtenderContractTest] 가 담당.
 * - suspend group 계약은 [AbstractSuspendGroupLockExtenderContractTest] 가 담당.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractGroupLockExtenderContractTest {

    /** 각 backend 가 자기 [LeaderGroupElector] 인스턴스를 제공한다. */
    protected abstract val elector: LeaderGroupElector

    private fun randomLockName(): String = "ctr-g-${Base58.randomString(8)}"

    // ── assertLocked() ────────────────────────────────────────────────────

    @Test
    fun `assertLocked passes inside group runIfLeader body`() {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()          // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLocked throws outside group runIfLeader body`() {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLocked()
        }
    }

    // ── isLocked() ───────────────────────────────────────────────────────

    @Test
    fun `isLocked returns false outside and true inside group body`() {
        val lockName = randomLockName()

        LockAssert.isLocked().shouldBeFalse()

        elector.runIfLeader(lockName) {
            LockAssert.isLocked().shouldBeTrue()
        }

        LockAssert.isLocked().shouldBeFalse()
    }

    // ── extendActiveLock() ────────────────────────────────────────────────

    @Test
    fun `extendActiveLock returns true when group lock is held`() {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLock(60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLock returns false after group runIfLeader body completes`() {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) { /* acquire and release */ }

        // scope ended — no active handle
        LockExtender.extendActiveLock(60.seconds).shouldBeFalse()
    }

    // ── extendActiveLockDetailed() ─────────────────────────────────────────

    @Test
    fun `extendActiveLockDetailed returns Extended when group lock is held`() {
        val lockName = randomLockName()
        var outcome: ExtendOutcome? = null

        elector.runIfLeader(lockName) {
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        (outcome is ExtendOutcome.Extended).shouldBeTrue()
    }

    @Test
    fun `extendActiveLockDetailed returns NotHeld outside group body`() {
        val outcome = LockExtender.extendActiveLockDetailed(60.seconds)

        (outcome is ExtendOutcome.NotHeld).shouldBeTrue()
    }

    // ── return value ──────────────────────────────────────────────────────

    @Test
    fun `runIfLeader returns action return value when group slot is acquired`() {
        val lockName = randomLockName()
        val result = elector.runIfLeader(lockName) { "group-contract-ok" }
        result shouldBeEqualTo "group-contract-ok"
    }
}

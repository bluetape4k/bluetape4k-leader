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
 * Backend-agnostic LockAssert/LockExtender × sync [LeaderGroupElector] contract.
 *
 * Each backend's contract test inherits this abstract class and provides its own [elector] instance.
 *
 * ## Verified Contracts (AC-1 / Issue #79)
 *
 * 1. `assertLocked()` — passes inside [LeaderGroupElector.runIfLeader] body; throws [IllegalStateException] outside
 * 2. `isLocked()` — returns `true` inside the body, `false` outside
 * 3. `extendActiveLock(d)` — returns `true` inside the body, `false` after completion
 * 4. `extendActiveLockDetailed(d)` — returns [ExtendOutcome.Extended]
 *
 * ## Usage
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
 * ## Scope Limitations
 *
 * - Single elector contracts are covered by [AbstractSyncLockExtenderContractTest].
 * - Suspend group contracts are covered by [AbstractSuspendGroupLockExtenderContractTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractGroupLockExtenderContractTest {

    /** Each backend provides its own [LeaderGroupElector] instance. */
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

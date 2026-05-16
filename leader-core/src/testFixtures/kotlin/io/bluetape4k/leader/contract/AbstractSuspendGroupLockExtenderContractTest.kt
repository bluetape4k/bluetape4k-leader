package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/**
 * Backend-agnostic LockAssert/LockExtender × suspend [SuspendLeaderGroupElector] contract.
 *
 * Each backend's contract test inherits this abstract class and provides its own [elector] instance.
 *
 * ## Verified Contracts (AC-1 / Issue #79)
 *
 * 1. `assertLockedSuspend()` — passes inside [SuspendLeaderGroupElector.runIfLeader] body; throws [IllegalStateException] outside
 * 2. `isLockedSuspend()` — returns `true` inside the body, `false` outside
 * 3. `extendActiveLockSuspend(d)` — returns `true` inside the body, `false` after completion
 * 4. `extendActiveLockDetailedSuspend(d)` — returns [ExtendOutcome.Extended]
 *
 * ## Usage
 *
 * ```kotlin
 * class RedissonSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {
 *     companion object : KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override val elector: SuspendLeaderGroupElector = RedissonSuspendLeaderGroupElector(client)
 * }
 * ```
 *
 * ## Scope Limitations
 *
 * - Sync group contracts are covered by [AbstractGroupLockExtenderContractTest].
 * - Single suspend contracts are covered by [AbstractSuspendLockExtenderContractTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSuspendGroupLockExtenderContractTest {

    /** Each backend provides its own [SuspendLeaderGroupElector] instance. */
    protected abstract val elector: SuspendLeaderGroupElector

    private fun randomLockName(): String = "ctr-sg-${Base58.randomString(8)}"

    // ── assertLockedSuspend() ──────────────────────────────────────────────

    @Test
    fun `assertLockedSuspend passes inside suspend group runIfLeader body`() = runSuspendIO {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()          // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLockedSuspend throws outside suspend group runIfLeader body`() = runSuspendIO {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLockedSuspend()
        }
    }

    // ── isLockedSuspend() ─────────────────────────────────────────────────

    @Test
    fun `isLockedSuspend returns false outside and true inside suspend group body`() = runSuspendIO {
        val lockName = randomLockName()

        LockAssert.isLockedSuspend().shouldBeFalse()

        elector.runIfLeader(lockName) {
            LockAssert.isLockedSuspend().shouldBeTrue()
        }

        LockAssert.isLockedSuspend().shouldBeFalse()
    }

    // ── extendActiveLockSuspend() ─────────────────────────────────────────

    @Test
    fun `extendActiveLockSuspend returns true when suspend group lock is held`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend(60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLockSuspend returns false after suspend group body completes`() = runSuspendIO {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) { /* acquire and release */ }

        // scope ended — no active handle in coroutineContext
        LockExtender.extendActiveLockSuspend(60.seconds).shouldBeFalse()
    }

    // ── extendActiveLockDetailedSuspend() ─────────────────────────────────

    @Test
    fun `extendActiveLockDetailedSuspend returns Extended when suspend group lock is held`() = runSuspendIO {
        val lockName = randomLockName()
        var outcome: ExtendOutcome? = null

        elector.runIfLeader(lockName) {
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        (outcome is ExtendOutcome.Extended).shouldBeTrue()
    }

    @Test
    fun `extendActiveLockDetailedSuspend returns NotHeld outside suspend group body`() = runSuspendIO {
        val outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)

        (outcome is ExtendOutcome.NotHeld).shouldBeTrue()
    }

    // ── return value ──────────────────────────────────────────────────────

    @Test
    fun `runIfLeader returns action return value when suspend group slot is acquired`() = runSuspendIO {
        val lockName = randomLockName()
        val result = elector.runIfLeader(lockName) { "suspend-group-contract-ok" }
        result shouldBeEqualTo "suspend-group-contract-ok"
    }
}

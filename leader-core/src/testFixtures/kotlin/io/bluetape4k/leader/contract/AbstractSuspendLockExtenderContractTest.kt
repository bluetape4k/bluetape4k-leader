package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Backend-agnostic LockAssert/LockExtender × suspend [SuspendLeaderElector] contract.
 *
 * Each backend's contract test inherits this abstract class and provides its own [elector] instance.
 *
 * ## Verified Contracts (AC-1 / Issue #79)
 *
 * 1. `assertLockedSuspend()` — passes inside [SuspendLeaderElector.runIfLeader] body; throws [IllegalStateException] outside
 * 2. `assertLockedSuspend(lockName)` — passes when name matches; throws [IllegalStateException] when mismatched
 * 3. `isLockedSuspend()` — returns `true` inside the body, `false` outside
 * 4. `extendActiveLockSuspend(d)` — returns `true` inside the body, `false` after completion
 * 5. `extendActiveLockDetailedSuspend(d)` — returns [ExtendOutcome.Extended]
 * 6. `extendActiveLockSuspend(lockName, d)` — returns `true` when name matches, `false` when mismatched
 *
 * ## Usage
 *
 * ```kotlin
 * class RedissonSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {
 *     companion object : KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override val elector: SuspendLeaderElector = RedissonSuspendLeaderElector(client)
 * }
 * ```
 *
 * ## Scope Limitations
 *
 * - Sync contracts are covered by [AbstractSyncLockExtenderContractTest].
 * - Group elector ([io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector]) contracts are delegated to a separate base.
 *   (As of T5, group elector does not implement handle push and is tracked separately.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSuspendLockExtenderContractTest {

    /** Each backend provides its own [SuspendLeaderElector] instance. */
    protected abstract val elector: SuspendLeaderElector

    private fun randomLockName(): String = "ctr-su-${Base58.randomString(8)}"

    // ── assertLockedSuspend() ──────────────────────────────────────────────

    @Test
    fun `assertLockedSuspend passes inside runIfLeader body`() = runSuspendIO {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()          // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLockedSuspend throws outside runIfLeader body`() = runSuspendIO {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLockedSuspend()
        }
    }

    @Test
    fun `assertLockedSuspend with matching lockName passes inside body`() = runSuspendIO {
        val lockName = randomLockName()
        var assertionPassed = false

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)  // must not throw
            assertionPassed = true
        }

        assertionPassed.shouldBeTrue()
    }

    @Test
    fun `assertLockedSuspend with mismatched lockName throws inside body`() = runSuspendIO {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) {
            assertFailsWith<IllegalStateException> {
                LockAssert.assertLockedSuspend("wrong-lock-name")
            }
        }
    }

    // ── isLockedSuspend() ─────────────────────────────────────────────────

    @Test
    fun `isLockedSuspend returns true inside runIfLeader body and false outside`() = runSuspendIO {
        val lockName = randomLockName()

        LockAssert.isLockedSuspend().shouldBeFalse()

        elector.runIfLeader(lockName) {
            LockAssert.isLockedSuspend().shouldBeTrue()
            LockAssert.isLockedSuspend(lockName).shouldBeTrue()
        }

        LockAssert.isLockedSuspend().shouldBeFalse()
    }

    @Test
    fun `isLockedSuspend with mismatched lockName returns false inside body`() = runSuspendIO {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) {
            LockAssert.isLockedSuspend("other-lock").shouldBeFalse()
        }
    }

    // ── extendActiveLockSuspend() ─────────────────────────────────────────

    @Test
    fun `extendActiveLockSuspend returns true when lock is held`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend(60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLockSuspend returns false after runIfLeader body completes`() = runSuspendIO {
        val lockName = randomLockName()

        elector.runIfLeader(lockName) { /* acquire and release */ }

        // scope ended — no active handle in coroutineContext
        LockExtender.extendActiveLockSuspend(60.seconds).shouldBeFalse()
    }

    @Test
    fun `extendActiveLockSuspend with matching lockName returns true`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend(lockName, 60.seconds)
        }

        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLockSuspend with mismatched lockName returns false`() = runSuspendIO {
        val lockName = randomLockName()
        var extended = true  // intentionally start as true to verify it becomes false

        elector.runIfLeader(lockName) {
            extended = LockExtender.extendActiveLockSuspend("wrong-lock-name", 60.seconds)
        }

        extended.shouldBeFalse()
    }

    // ── extendActiveLockDetailedSuspend() ─────────────────────────────────

    @Test
    fun `extendActiveLockDetailedSuspend returns Extended when lock is held`() = runSuspendIO {
        val lockName = randomLockName()
        var outcome: ExtendOutcome? = null

        elector.runIfLeader(lockName) {
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        (outcome is ExtendOutcome.Extended).shouldBeTrue()
    }

    @Test
    fun `extendActiveLockDetailedSuspend returns NotHeld outside runIfLeader body`() = runSuspendIO {
        val outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)

        (outcome is ExtendOutcome.NotHeld).shouldBeTrue()
    }

    // ── return value ──────────────────────────────────────────────────────

    @Test
    fun `runIfLeader returns action return value when lock is acquired`() = runSuspendIO {
        val lockName = randomLockName()
        val result = elector.runIfLeader(lockName) { "suspend-contract-ok" }
        result shouldBeEqualTo "suspend-contract-ok"
    }

    // ── concurrent mutex ─────────────────────────────────────────────────

    @Test
    fun `runIfLeader enforces mutual exclusion under concurrent coroutine access`() = runSuspendIO {
        val lockName = randomLockName()
        val currentHolders = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        SuspendedJobTester()
            .workers(8)
            .rounds(4)
            .add {
                elector.runIfLeader(lockName) {
                    val n = currentHolders.incrementAndGet()
                    maxConcurrent.getAndUpdate { max(it, n) }
                    delay(5.milliseconds)
                    currentHolders.decrementAndGet()
                }
            }
            .run()

        maxConcurrent.get() shouldBeEqualTo 1
    }

    // ── AC-6 concurrent extends stress ───────────────────────────────────

    /**
     * AC-6: N concurrent coroutine workers each acquire their own lock and extend it M times.
     *
     * Verifies that concurrent suspend extend calls from multiple leaders are race-free —
     * all backend extend operations succeed with no torn writes or exceptions.
     *
     * Note: LockExtender suspend variants use CoroutineContext (LockHandleElement) so
     * extend calls are valid only within the coroutine that holds the lock.
     * This test exercises concurrent backend load from N independent leader coroutines,
     * each extending their own slot.
     */
    @Test
    fun `AC-6 concurrent suspend extends race-free — N workers each extend their own lock`() = runSuspendIO {
        val successCount = AtomicInteger(0)
        val extendsPerRound = 5

        val rounds = 10
        SuspendedJobTester()
            .workers(8)
            .rounds(rounds)
            .add {
                val lockName = randomLockName()
                elector.runIfLeader(lockName) {
                    repeat(extendsPerRound) { i ->
                        val outcome = LockExtender.extendActiveLockDetailedSuspend((10 + i * 5).seconds)
                        if (outcome is ExtendOutcome.Extended) successCount.incrementAndGet()
                    }
                }
            }
            .run()

        // SuspendedJobTester: rounds = total invocations (workers = concurrency level)
        // 10 rounds × 5 extends each = 50 successful extends
        successCount.get() shouldBeEqualTo rounds * extendsPerRound
    }

    /**
     * AC-6b: Single-leader suspend extends with randomized durations — last-write-wins,
     * no exception thrown, sequential extend calls on the same lock are safe.
     */
    @Test
    fun `AC-6b sequential suspend extends with random durations are all successful`() = runSuspendIO {
        val lockName = randomLockName()
        val successCount = AtomicInteger(0)

        elector.runIfLeader(lockName) {
            repeat(20) { i ->
                val duration = (10 + Random.nextInt(50)).seconds
                val outcome = LockExtender.extendActiveLockDetailedSuspend(duration)
                if (outcome is ExtendOutcome.Extended) successCount.incrementAndGet()
            }
        }

        successCount.get() shouldBeEqualTo 20
    }
}

package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Backend-agnostic LockAssert/LockExtender × sync [LeaderElector] contract.
 *
 * Each backend's contract test inherits this abstract class and provides its own [elector] instance.
 *
 * ## Verified Contracts (AC-1 / Issue #79)
 *
 * 1. `assertLocked()` — passes inside [LeaderElector.runIfLeader] body; throws [IllegalStateException] outside
 * 2. `assertLocked(lockName)` — passes when name matches; throws [IllegalStateException] when mismatched
 * 3. `isLocked()` — returns `true` inside the body, `false` outside
 * 4. `extendActiveLock(d)` — returns `true` inside the body, `false` after completion
 * 5. `extendActiveLockDetailed(d)` — returns [ExtendOutcome.Extended]
 * 6. `extendActiveLock(lockName, d)` — returns `true` when name matches, `false` when mismatched
 *
 * ## Usage
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
 * ## Scope Limitations
 *
 * - Group elector ([io.bluetape4k.leader.LeaderGroupElector]) contracts are delegated to a separate base.
 *   (As of T5, group elector does not implement handle push and is tracked separately.)
 * - Suspend contracts are covered by [AbstractSuspendLockExtenderContractTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSyncLockExtenderContractTest {

    /** Each backend provides its own [LeaderElector] instance. */
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

    // ── concurrent mutex ─────────────────────────────────────────────────

    @Test
    fun `runIfLeader enforces mutual exclusion under concurrent access`() {
        val lockName = randomLockName()
        val currentHolders = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(8)
            .rounds(4)
            .add {
                elector.runIfLeader(lockName) {
                    val n = currentHolders.incrementAndGet()
                    maxConcurrent.getAndUpdate { max(it, n) }
                    Thread.sleep(5)
                    currentHolders.decrementAndGet()
                }
            }
            .run()

        maxConcurrent.get() shouldBeEqualTo 1
    }

    // ── AC-6 concurrent extends stress ───────────────────────────────────

    /**
     * AC-6: N concurrent workers each acquire their own lock and extend it M times.
     *
     * Verifies that concurrent extend calls from multiple leaders are race-free —
     * all backend extend operations succeed with no torn writes or exceptions.
     *
     * Note: LockExtender uses ThreadLocal so extend calls are valid only on the
     * thread that holds the lock. This test exercises concurrent backend load
     * from N independent leader threads, each extending their own slot.
     */
    @Test
    fun `AC-6 concurrent extends race-free — N workers each extend their own lock`() {
        val successCount = AtomicInteger(0)
        val extendsPerRound = 5

        MultithreadingTester()
            .workers(8)
            .rounds(10)
            .add {
                val lockName = randomLockName()
                elector.runIfLeader(lockName) {
                    repeat(extendsPerRound) { i ->
                        val outcome = LockExtender.extendActiveLockDetailed((10 + i * 5).seconds)
                        if (outcome is ExtendOutcome.Extended) successCount.incrementAndGet()
                    }
                }
            }
            .run()

        // 8 workers × 10 rounds × 5 extends each = 400 successful extends
        successCount.get() shouldBeEqualTo 8 * 10 * extendsPerRound
    }

    /**
     * AC-6b: Single-leader extends with randomized durations — last-write-wins,
     * no exception thrown, sequential extend calls on the same lock are safe.
     */
    @Test
    fun `AC-6b sequential extends with random durations are all successful`() {
        val lockName = randomLockName()
        val successCount = AtomicInteger(0)

        elector.runIfLeader(lockName) {
            repeat(20) { i ->
                val duration = (10 + Random.nextInt(50)).seconds
                val outcome = LockExtender.extendActiveLockDetailed(duration)
                if (outcome is ExtendOutcome.Extended) successCount.incrementAndGet()
            }
        }

        successCount.get() shouldBeEqualTo 20
    }
}

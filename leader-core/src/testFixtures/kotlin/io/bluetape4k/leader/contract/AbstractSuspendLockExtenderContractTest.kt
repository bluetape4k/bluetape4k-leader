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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Backend-agnostic 한 LockAssert/LockExtender × suspend [SuspendLeaderElector] contract.
 *
 * 각 backend 의 contract test 가 이 abstract class 를 상속하여 [elector] 를 제공한다.
 *
 * ## 검증 계약 (AC-1 / Issue #79)
 *
 * 1. `assertLockedSuspend()` — [SuspendLeaderElector.runIfLeader] 본문 안에서는 pass, 밖에서는 [IllegalStateException]
 * 2. `assertLockedSuspend(lockName)` — 이름 일치 시 pass, 불일치 시 [IllegalStateException]
 * 3. `isLockedSuspend()` — 본문 안 `true`, 밖 `false`
 * 4. `extendActiveLockSuspend(d)` — 본문 안 `true`, 완료 후 `false`
 * 5. `extendActiveLockDetailedSuspend(d)` — [ExtendOutcome.Extended] 반환
 * 6. `extendActiveLockSuspend(lockName, d)` — 이름 일치 `true`, 불일치 `false`
 *
 * ## 사용 방법
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
 * ## 범위 한계
 *
 * - sync 계약은 [AbstractSyncLockExtenderContractTest] 가 담당.
 * - Group elector ([io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector]) 계약은 별도 base 로 위임.
 *   (T5 기준 group elector 는 handle push 를 구현하지 않으므로 별도 tracking.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSuspendLockExtenderContractTest {

    /** 각 backend 가 자기 [SuspendLeaderElector] 인스턴스를 제공한다. */
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
}

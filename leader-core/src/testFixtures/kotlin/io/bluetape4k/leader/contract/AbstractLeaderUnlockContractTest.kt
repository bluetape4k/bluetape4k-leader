package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

/**
 * Regression base for `runIfLeader` unlock contracts across all [LeaderElector] backends.
 *
 * ## Verified Contracts (CRITICAL — R-19, C-4)
 *
 * All [LeaderElector] implementations must guarantee the following:
 *
 * 1. **Normal body return → lock released** — after `runIfLeader` body returns a value or `null` normally,
 *    the same client must be able to re-acquire the same lockName immediately, without waiting for lease expiry.
 * 2. **Body throws → lock released** — even when the `runIfLeader` body throws an exception,
 *    unlock must be guaranteed via try-finally. The same client must be able to re-acquire immediately after
 *    the exception propagates.
 *
 * ## Usage
 *
 * Each of the 6 backends (`Local`, `Lettuce`, `Redisson`, `Mongo` sync, `Hazelcast`, `ExposedJdbc`)
 * inherits this class and overrides [newElection]. Backends requiring testcontainers use
 * `@Tag("integration")` + `XxxServer.Launcher.xxx` standard pattern.
 *
 * ```kotlin
 * @Tag("integration")
 * class RedissonUnlockContractTest : AbstractLeaderUnlockContractTest() {
 *     companion object: KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override fun newElection(): LeaderElector = RedissonLeaderElector(client)
 * }
 * ```
 *
 * ## Scope Limitations
 *
 * This base verifies only backend unlock contracts. AOP `LeaderAspectFailureMode { RETHROW, SKIP }` matrix
 * verification is delegated to Phase 5 `T5.12` (`*UnlockContractTest` Aspect integration).
 */
abstract class AbstractLeaderUnlockContractTest {

    companion object: KLogging() {
        private const val SAMPLE_RESULT = "ok"
        private val sampleException = RuntimeException("intentional body failure")
    }

    /**
     * Creates a [LeaderElector] instance under test.
     *
     * May return a new instance on each call, but it must point to the same backend (lock namespace).
     * Otherwise, lock isolation between two instances causes false positives (e.g., Local's static lock map).
     */
    protected abstract fun newElection(): LeaderElector

    private fun randomLockName(): String = "unlock-${Base58.randomString(8)}"

    @Test
    fun `body normal return - lock must be released immediately and re-acquirable`() {
        val election = newElection()
        val lockName = randomLockName()

        val first = election.runIfLeader(lockName) { SAMPLE_RESULT }
        first shouldBeEqualTo SAMPLE_RESULT

        // re-acquire immediately — no waiting for lease expiry
        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second.shouldNotBeNull()
        second shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `body throws - lock must be released and re-acquirable even after exception propagation`() {
        val election = newElection()
        val lockName = randomLockName()

        assertFailsWith<RuntimeException> {
            election.runIfLeader<Unit>(lockName) {
                throw sampleException
            }
        }

        // re-acquire immediately — try-finally unlock guaranteed
        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second.shouldNotBeNull()
        second shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `body returns null - lock must be released and re-acquirable after null return`() {
        val election = newElection()
        val lockName = randomLockName()

        val first = election.runIfLeader<String?>(lockName) { null }
        first.shouldBeNull()

        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second shouldBeEqualTo SAMPLE_RESULT
    }
}

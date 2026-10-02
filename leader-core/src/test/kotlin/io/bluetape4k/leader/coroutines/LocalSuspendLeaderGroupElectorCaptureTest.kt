package io.bluetape4k.leader.coroutines

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.internal.LeaderLockHandleCapture
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LocalSuspendLeaderGroupElector] capture integration test.
 *
 * Suspend group election propagates lock handles through [LockHandleElement] only.
 * It must not publish handles through [LeaderLockHandleCapture], because ThreadLocal
 * set/clear can happen on different carrier threads after dispatcher hops.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("NonAsciiCharacters")
class LocalSuspendLeaderGroupElectorCaptureTest {

    companion object: KLoggingChannel()

    private val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))

    @BeforeEach
    fun clearThreadLocalCapture() {
        LeaderLockHandleCapture.clear()
    }

    private fun randomLockName() = "group-lock-${Base58.randomString(8)}"

    @Test
    fun `group runIfLeader uses LockHandleElement only across dispatcher hops`() = runSuspendIO {
        val checks = AtomicInteger()

        SuspendedJobTester()
            .rounds(100)
            .add {
                election.runIfLeader(randomLockName()) {
                    assertCoroutineContextOnly(checks)

                    withContext(Dispatchers.IO) {
                        assertCoroutineContextOnly(checks)
                    }

                    withContext(Dispatchers.Default) {
                        assertCoroutineContextOnly(checks)
                    }
                }
            }
            .run()

        checks.get() shouldBeEqualTo 300
        LeaderLockHandleCapture.poll().shouldBeNull()
    }

    private suspend fun assertCoroutineContextOnly(checks: AtomicInteger) {
        LeaderLockHandleCapture.poll().shouldBeNull()
        currentCoroutineContext()[LockHandleElement].shouldNotBeNull()
        LockAssert.isLockedSuspend().shouldBeTrue()

        checks.incrementAndGet().apply {
            log.debug { "check=$this" }
        }
    }
}

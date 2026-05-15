package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.internal.ExtendDelegate
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stress tests for [LeaderLeaseAutoExtender] — N=100 concurrent watchdogs and async-extend dispatch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("NonAsciiCharacters")
class LeaderLeaseAutoExtenderStressTest {

    @AfterEach
    fun resetConfig() {
        LeaderLeaseAutoExtender.configure(
            watchdogThreads = LeaderLeaseAutoExtender.DEFAULT_WATCHDOG_THREADS,
            asyncExtend = false,
        )
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.restart()
    }

    private class StressTestDelegate : ExtendDelegate {
        val extendCalls = AtomicInteger(0)
        private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            extendCalls.incrementAndGet()
            return ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        }

        override fun isHeld(): Boolean = true
    }

    @Test
    fun `N=100 concurrent watchdogs - all delegates called at least once within 5s`() = runSuspendIO {
        val n = 100
        val delegates = List(n) { StressTestDelegate() }
        val watchdogs = delegates.map {
            LeaderLeaseAutoExtender.start(true, 3.seconds, it)
        }

        delay(5.seconds)

        watchdogs.forEach { it.close() }

        val allCalled = delegates.all { it.extendCalls.get() >= 1 }
        allCalled.shouldBeTrue()
    }

    @Test
    fun `asyncExtend - slow delegates dispatched concurrently with single scheduler thread`() = runSuspendIO {
        LeaderLeaseAutoExtender.configure(watchdogThreads = 1, asyncExtend = true)
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.restart()

        val extendStartedLatch = CountDownLatch(3)

        val delegates = List(3) {
            object : ExtendDelegate {
                private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
                override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

                override fun extend(lockAtMostFor: Duration): ExtendOutcome {
                    extendStartedLatch.countDown()
                    // Simulate a slow backend — blocks for 500ms per extend call
                    Thread.sleep(500)
                    return ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                }

                override fun isHeld(): Boolean = true
            }
        }

        // cadence = 300ms / 3 = 100ms (above MIN_RENEWAL_PERIOD of 25ms)
        val watchdogs = delegates.map {
            LeaderLeaseAutoExtender.start(true, 300.milliseconds, it)
        }

        // If async: all 3 start within one cadence window (~600ms). If serial: would take ~1500ms.
        val allConcurrent = extendStartedLatch.await(600, TimeUnit.MILLISECONDS)

        watchdogs.forEach { it.close() }

        // Virtual-thread dispatch allows all 3 slow extends to start concurrently.
        allConcurrent.shouldBeTrue()
    }
}

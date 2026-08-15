package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.internal.ExtendDelegate
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withAlias
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

        try {
            await
                .withAlias("LeaderLeaseAutoExtender delegates called: n=$n")
                .atMost(5.seconds)
                .withPollInterval(50.milliseconds)
                .untilAsserted {
                    val missingDelegates = delegates.count { it.extendCalls.get() < 1 }
                    missingDelegates shouldBeEqualTo 0
                }
        } finally {
            watchdogs.forEach { it.close() }
        }
    }

    @Test
    fun `asyncExtend - slow delegates dispatched concurrently with single scheduler thread`() = runSuspendIO {
        LeaderLeaseAutoExtender.configure(watchdogThreads = 1, asyncExtend = true)
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.restart()

        val extendStartedCount = AtomicInteger(0)
        val releaseSlowExtends = CompletableFuture<Unit>()

        val delegates = List(3) {
            object : ExtendDelegate {
                private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
                override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

                override fun extend(lockAtMostFor: Duration): ExtendOutcome {
                    extendStartedCount.incrementAndGet()
                    try {
                        // A controllable gate models a slow backend without wall-clock sleep.
                        releaseSlowExtends.get(5, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        throw IllegalStateException(
                            "slow delegate gate timed out: started=${extendStartedCount.get()}/3",
                            e,
                        )
                    }
                    return ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                }

                override fun isHeld(): Boolean = true
            }
        }

        // cadence = 300ms / 3 = 100ms (above MIN_RENEWAL_PERIOD of 25ms)
        val watchdogs = delegates.map {
            LeaderLeaseAutoExtender.start(true, 300.milliseconds, it)
        }

        try {
            // Async dispatch must start all delegates while the first three calls are gated.
            await
                .withAlias("LeaderLeaseAutoExtender async delegates: started=${extendStartedCount.get()}/3")
                .atMost(2.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    extendStartedCount.get() shouldBeEqualTo 3
                }
        } finally {
            releaseSlowExtends.complete(Unit)
            watchdogs.forEach { it.close() }
        }
    }
}

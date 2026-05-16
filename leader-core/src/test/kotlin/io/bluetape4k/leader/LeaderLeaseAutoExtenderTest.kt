package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome.Extended
import io.bluetape4k.leader.internal.ExtendDelegate
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderLeaseAutoExtenderTest {

    @AfterEach
    fun restoreScheduler() {
        // Ensure the shared singleton is always restored after each test that may call shutdown().
        LeaderLeaseAutoExtender.restart()
    }

    private fun countingDelegate(calls: AtomicInteger): ExtendDelegate = object : ExtendDelegate {
        override val lastExtendDeadline: AtomicReference<Instant> = AtomicReference(Instant.EPOCH)
        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            calls.incrementAndGet()
            return Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        }
        override fun isHeld(): Boolean = true
    }

    @Test
    fun `disabled watchdog does not call extender`() = runSuspendIO {
        val calls = AtomicInteger(0)
        val watchdog = LeaderLeaseAutoExtender.start(false, 100.milliseconds, countingDelegate(calls))

        delay(120.milliseconds)
        watchdog.close()

        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `watchdog stops after close`() = runSuspendIO {
        val calls = AtomicInteger(0)
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, countingDelegate(calls))

        delay(120.milliseconds)
        watchdog.close()
        val afterClose = calls.get()
        delay(120.milliseconds)

        calls.get() shouldBeEqualTo afterClose
    }

    @Test
    fun `cancelled watchdog tasks are removed from scheduler queue`() {
        val scheduler = scheduler()
        val before = scheduler.queue.size

        val watchdogs = (1..20).map {
            LeaderLeaseAutoExtender.start(true, 30.seconds, countingDelegate(AtomicInteger()))
        }
        watchdogs.forEach { it.close() }

        scheduler.queue.size shouldBeEqualTo before
    }

    @Test
    fun `shutdown stops the scheduler`() {
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `restart recreates the scheduler after shutdown`() {
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()

        LeaderLeaseAutoExtender.restart()
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
    }

    @Test
    fun `start after shutdown and restart works normally`() = runSuspendIO {
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.restart()

        val calls = AtomicInteger(0)
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, countingDelegate(calls))
        delay(200.milliseconds)
        watchdog.close()

        (calls.get() > 0).shouldBeTrue()
    }

    @Test
    fun `start on shutdown scheduler returns NoopCloseable without throwing`() {
        LeaderLeaseAutoExtender.shutdown()

        val result = runCatching {
            LeaderLeaseAutoExtender.start(true, 90.milliseconds, countingDelegate(AtomicInteger()))
        }

        result.isSuccess.shouldBeTrue()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    private fun scheduler(): ScheduledThreadPoolExecutor {
        val field = LeaderLeaseAutoExtender::class.java.getDeclaredField("scheduler").apply {
            isAccessible = true
        }
        return field.get(LeaderLeaseAutoExtender) as ScheduledThreadPoolExecutor
    }
}

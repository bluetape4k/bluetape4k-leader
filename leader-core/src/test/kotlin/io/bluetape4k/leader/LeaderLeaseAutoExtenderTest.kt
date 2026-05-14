package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderLeaseAutoExtenderTest {

    @AfterEach
    fun restoreScheduler() {
        // Ensure the shared singleton is always restored after each test that may call shutdown().
        LeaderLeaseAutoExtender.restart()
    }

    @Test
    fun `disabled watchdog does not call extender`() = runSuspendIO {
        val calls = AtomicInteger(0)

        @Suppress("DEPRECATION")
        val watchdog = LeaderLeaseAutoExtender.start(false, 100.milliseconds) {
            calls.incrementAndGet()
            true
        }

        delay(120.milliseconds)
        watchdog.close()

        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `watchdog stops after close`() = runSuspendIO {
        val calls = AtomicInteger(0)

        @Suppress("DEPRECATION")
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds) {
            calls.incrementAndGet()
            true
        }

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

        @Suppress("DEPRECATION")
        val watchdogs = (1..20).map {
            LeaderLeaseAutoExtender.start(true, 30.seconds) { true }
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
        @Suppress("DEPRECATION")
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds) {
            calls.incrementAndGet()
            true
        }
        delay(200.milliseconds)
        watchdog.close()

        (calls.get() > 0).shouldBeTrue()
    }

    @Test
    fun `start on shutdown scheduler returns NoopCloseable without throwing`() {
        LeaderLeaseAutoExtender.shutdown()

        val result = runCatching {
            @Suppress("DEPRECATION")
            LeaderLeaseAutoExtender.start(true, 90.milliseconds) { true }
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

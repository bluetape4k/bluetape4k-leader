package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderLeaseAutoExtenderTest {

    @Test
    fun `disabled watchdog does not call extender`() = runSuspendIO {
        val calls = AtomicInteger(0)

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

        val watchdogs = (1..20).map {
            LeaderLeaseAutoExtender.start(true, 30.seconds) { true }
        }
        watchdogs.forEach { it.close() }

        scheduler.queue.size shouldBeEqualTo before
    }

    private fun scheduler(): ScheduledThreadPoolExecutor {
        val field = LeaderLeaseAutoExtender::class.java.getDeclaredField("scheduler").apply {
            isAccessible = true
        }
        return field.get(LeaderLeaseAutoExtender) as ScheduledThreadPoolExecutor
    }
}

package io.bluetape4k.leader.spring

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.spring.boot.LeaderLeaseAutoExtenderLifecycle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderLeaseAutoExtenderLifecycleTest {

    @BeforeEach
    fun resetState() {
        // Reset ref-count and ensure scheduler is running before each test.
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value = 0
        LeaderLeaseAutoExtenderLifecycle.activeConfiguration = null
        LeaderLeaseAutoExtender.restart()
    }

    @AfterEach
    fun restoreScheduler() {
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value = 0
        LeaderLeaseAutoExtenderLifecycle.activeConfiguration = null
        LeaderLeaseAutoExtender.restart()
    }

    @Test
    fun `destroy shuts down scheduler when last context closes`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()  // count = 1

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        lifecycle.destroy()             // count = 0 → shutdown

        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `destroy is idempotent — repeated destroy does not underflow counter`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()  // count = 1 (registered)

        lifecycle.destroy()             // count = 0 → shutdown
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()

        // Second destroy must be a no-op; counter must not go negative.
        lifecycle.destroy()
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value shouldBeEqualTo 0
    }

    @Test
    fun `afterPropertiesSet is idempotent — repeated call does not double-increment counter`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()  // count = 1 (registered)
        lifecycle.afterPropertiesSet()  // no-op registration; count stays 1

        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value shouldBeEqualTo 1

        lifecycle.destroy()             // count = 0 → shutdown
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `afterPropertiesSet restarts a shutdown scheduler`() {
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()

        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()  // count = 1, restarts scheduler

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
    }

    @Test
    fun `afterPropertiesSet is no-op when scheduler is already running`() {
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
    }

    @Test
    fun `destroy with multiple active contexts does not shut down scheduler`() {
        val lifecycle1 = LeaderLeaseAutoExtenderLifecycle()
        val lifecycle2 = LeaderLeaseAutoExtenderLifecycle()
        lifecycle1.afterPropertiesSet()  // count = 1
        lifecycle2.afterPropertiesSet()  // count = 2

        lifecycle1.destroy()             // count = 1 → scheduler still running
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        lifecycle2.destroy()             // count = 0 → shutdown
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `conflicting explicit context configuration is rejected without overwriting owner`() {
        val owner = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 2, watchdogAsyncExtend = false)
        val conflicting = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 4, watchdogAsyncExtend = true)
        owner.afterPropertiesSet()

        assertFailsWith<IllegalStateException> {
            conflicting.afterPropertiesSet()
        }

        LeaderLeaseAutoExtender.watchdogThreadCount() shouldBeEqualTo 2
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value shouldBeEqualTo 1

        owner.destroy()
        conflicting.destroy()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `conflicting explicit context configuration is rejected in reverse registration order`() {
        val owner = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 4, watchdogAsyncExtend = true)
        val conflicting = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 2, watchdogAsyncExtend = false)
        owner.afterPropertiesSet()

        assertFailsWith<IllegalStateException> {
            conflicting.afterPropertiesSet()
        }

        LeaderLeaseAutoExtender.watchdogThreadCount() shouldBeEqualTo 4
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.value shouldBeEqualTo 1

        conflicting.destroy()
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
        owner.destroy()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `matching explicit contexts keep scheduler alive in reverse close order`() {
        val first = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 3, watchdogAsyncExtend = true)
        val second = LeaderLeaseAutoExtenderLifecycle(watchdogThreads = 3, watchdogAsyncExtend = true)
        first.afterPropertiesSet()
        second.afterPropertiesSet()

        second.destroy()
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        first.destroy()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }
}

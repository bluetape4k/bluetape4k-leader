package io.bluetape4k.leader.spring

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
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.set(0)
        LeaderLeaseAutoExtender.restart()
    }

    @AfterEach
    fun restoreScheduler() {
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.set(0)
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
        LeaderLeaseAutoExtenderLifecycle.activeContextCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `afterPropertiesSet is idempotent — repeated call does not double-increment counter`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()  // count = 1 (registered)
        lifecycle.afterPropertiesSet()  // no-op registration; count stays 1

        LeaderLeaseAutoExtenderLifecycle.activeContextCount.get() shouldBeEqualTo 1

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
}

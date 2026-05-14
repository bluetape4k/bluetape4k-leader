package io.bluetape4k.leader.spring

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
    fun ensureSchedulerRunning() {
        // Guarantee a clean running scheduler before each test regardless of prior state.
        LeaderLeaseAutoExtender.restart()
    }

    @AfterEach
    fun restoreScheduler() {
        LeaderLeaseAutoExtender.restart()
    }

    @Test
    fun `destroy shuts down LeaderLeaseAutoExtender scheduler`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        lifecycle.destroy()

        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `destroy is idempotent — calling twice does not throw`() {
        val lifecycle = LeaderLeaseAutoExtenderLifecycle()

        lifecycle.destroy()
        lifecycle.destroy()

        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()
    }

    @Test
    fun `afterPropertiesSet restarts a shutdown scheduler`() {
        LeaderLeaseAutoExtender.shutdown()
        LeaderLeaseAutoExtender.isShutdown().shouldBeTrue()

        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
    }

    @Test
    fun `afterPropertiesSet is no-op when scheduler is already running`() {
        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()

        val lifecycle = LeaderLeaseAutoExtenderLifecycle()
        lifecycle.afterPropertiesSet()

        LeaderLeaseAutoExtender.isShutdown().shouldBeFalse()
    }
}

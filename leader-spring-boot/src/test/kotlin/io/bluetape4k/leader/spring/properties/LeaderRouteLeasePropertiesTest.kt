package io.bluetape4k.leader.spring.properties

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class LeaderRouteLeasePropertiesTest {

    @Test
    fun `defaults expose the bounded request lease contract`() {
        val properties = LeaderRouteLeaseProperties()

        properties.maxBlockingWaitTime shouldBeEqualTo Duration.ofSeconds(5)
        properties.maxConcurrentAcquires shouldBeEqualTo 256
        properties.maxConcurrentCleanups shouldBeEqualTo 256
        properties.maxAcquireQueueDepth shouldBeEqualTo 1024
        properties.maxCleanupQueueDepth shouldBeEqualTo 1024
        properties.maxMvcBlockingAcquires shouldBeEqualTo 32
        properties.maxActiveLeases shouldBeEqualTo 10_000
        properties.maxResidualLeases shouldBeEqualTo 1_024
        properties.maxWatchdogInFlight shouldBeEqualTo 256
        properties.maxLeaseLifetime shouldBeEqualTo Duration.ofMinutes(10)
        properties.minimumAutoExtendLeaseTime shouldBeEqualTo Duration.ofMillis(100)
        properties.maxExpectedExtensionLatency shouldBeEqualTo Duration.ofMillis(50)
        properties.drainTimeout shouldBeEqualTo Duration.ofSeconds(30)
        properties.effectiveActiveCapacity shouldBeEqualTo 1_024
    }

    @Test
    fun `yaml keys bind through the route guard lease namespace`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.route-guard.lease.max-blocking-wait-time" to "250ms",
                "bluetape4k.leader.route-guard.lease.max-concurrent-acquires" to "4",
                "bluetape4k.leader.route-guard.lease.max-concurrent-cleanups" to "3",
                "bluetape4k.leader.route-guard.lease.max-acquire-queue-depth" to "8",
                "bluetape4k.leader.route-guard.lease.max-cleanup-queue-depth" to "9",
                "bluetape4k.leader.route-guard.lease.max-mvc-blocking-acquires" to "2",
                "bluetape4k.leader.route-guard.lease.max-active-leases" to "10",
                "bluetape4k.leader.route-guard.lease.max-residual-leases" to "4",
                "bluetape4k.leader.route-guard.lease.max-watchdog-in-flight" to "3",
                "bluetape4k.leader.route-guard.lease.max-lease-lifetime" to "2m",
                "bluetape4k.leader.route-guard.lease.minimum-auto-extend-lease-time" to "200ms",
                "bluetape4k.leader.route-guard.lease.max-expected-extension-latency" to "50ms",
                "bluetape4k.leader.route-guard.lease.drain-timeout" to "1m",
            ),
        )

        val properties = Binder(source)
            .bind("bluetape4k.leader.route-guard.lease", LeaderRouteLeaseProperties::class.java)
            .get()

        properties.maxBlockingWaitTime shouldBeEqualTo Duration.ofMillis(250)
        properties.maxConcurrentAcquires shouldBeEqualTo 4
        properties.maxCleanupQueueDepth shouldBeEqualTo 9
        properties.maxActiveLeases shouldBeEqualTo 10
        properties.maxResidualLeases shouldBeEqualTo 4
        properties.drainTimeout shouldBeEqualTo Duration.ofMinutes(1)
    }

    @Test
    fun `invalid capacity and duration relationships fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(maxConcurrentAcquires = 0).validateForLeaseMode()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(maxAcquireQueueDepth = 65_537).validateForLeaseMode()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(maxConcurrentAcquires = 2, maxMvcBlockingAcquires = 3).validateForLeaseMode()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(maxActiveLeases = 2, maxResidualLeases = 3).validateForLeaseMode()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(
                minimumAutoExtendLeaseTime = Duration.ofMillis(10),
                maxExpectedExtensionLatency = Duration.ofMillis(20),
            ).validateForLeaseMode()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteLeaseProperties(drainTimeout = Duration.ZERO).validateForLeaseMode()
        }
    }

    @Test
    fun `effective active capacity is derived and never bindable`() {
        val properties = LeaderRouteLeaseProperties(maxActiveLeases = 7, maxResidualLeases = 3)
        properties.effectiveActiveCapacity shouldBeEqualTo 3
    }
}

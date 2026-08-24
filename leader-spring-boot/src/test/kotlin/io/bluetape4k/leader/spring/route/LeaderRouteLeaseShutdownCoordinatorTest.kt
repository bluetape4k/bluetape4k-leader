package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.properties.LeaderRouteAuthorityMode
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderRouteLeaseShutdownCoordinatorTest {

    @Test
    fun `state graph drains before closing and then rejects acquire`() {
        val coordinator = LeaderRouteLeaseShutdownCoordinator(100.milliseconds)
        coordinator.quiesce().shouldBeTrue()
        coordinator.acceptsAcquire().shouldBeFalse()
        coordinator.drain() shouldBeEqualTo LeaderRouteLeaseShutdownCoordinator.State.DRAINED
        coordinator.close()
        coordinator.runtimeState shouldBeEqualTo LeaderRouteLeaseShutdownCoordinator.State.CLOSED
    }

    @Test
    fun `residual at deadline produces terminal closed with leaks`() {
        val coordinator = LeaderRouteLeaseShutdownCoordinator(
            drainTimeout = 1.milliseconds,
            residualLeases = { 1 },
        )
        coordinator.drain() shouldBeEqualTo LeaderRouteLeaseShutdownCoordinator.State.CLOSED_WITH_LEAKS
        coordinator.acceptsAcquire().shouldBeFalse()
    }

    @Test
    fun `handle registration is linearized with drain`() {
        val releases = AtomicInteger()
        val coordinator = LeaderRouteLeaseShutdownCoordinator(100.milliseconds)
        val key = Any()

        coordinator.registerHandle(key) { releases.incrementAndGet() }.shouldBeTrue()
        coordinator.drain() shouldBeEqualTo LeaderRouteLeaseShutdownCoordinator.State.DRAINED
        releases.get() shouldBeEqualTo 1
        coordinator.registerHandle(Any()) { releases.incrementAndGet() }.shouldBeFalse()
    }

    @Test
    fun `rejected lifetime scheduling releases an already acquired delegate`() {
        val lifetimeScheduler = ScheduledThreadPoolExecutor(1).apply { shutdown() }
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 10.milliseconds, leaseTime = 1.seconds, nodeId = "scheduler-race"),
        )
        val properties = LeaderRouteGuardProperties(
            enabled = true,
            authorityMode = LeaderRouteAuthorityMode.LEASE,
            lease = LeaderRouteLeaseProperties(drainTimeout = java.time.Duration.ofMillis(100)),
        )
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = elector,
            suspendAcquirer = null,
            properties = properties.lease,
            lifetimeScheduler = lifetimeScheduler,
        )

        runtime.tryAcquire(LeaderSlot("scheduler-race", "node")).shouldBeNull()
        val deadline = System.nanoTime() + 1.seconds.inWholeNanoseconds
        while (runtime.activeLeases != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        runtime.activeLeases shouldBeEqualTo 0
        elector.tryAcquire(LeaderSlot("scheduler-race", "node"))?.release()

        runtime.close()
    }
}

package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.properties.LeaderRouteAuthorityMode
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties
import io.bluetape4k.junit5.coroutines.runSuspendIO
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.time.Instant
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
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
        await.atMost(5.seconds).untilAsserted {
            runtime.activeLeases shouldBeEqualTo 0
        }
        elector.tryAcquire(LeaderSlot("scheduler-race", "node"))?.release()

        runtime.close()
    }

    @Test
    fun `lifetime callback cannot leave a stale shutdown registration`() {
        val releaseCount = AtomicInteger()
        val acquireCount = AtomicInteger()
        val handle = object : LeaderLeaseHandle {
            override val lockName: String = "shutdown-race"
            override val auditLeaderId: String = "node"
            override val acquiredAt: Instant = Instant.now()
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome =
                ExtendOutcome.Extended(Instant.now())
            override fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD
            override fun isStillHeld(): Boolean = true
            override fun release() {
                releaseCount.incrementAndGet()
            }
        }
        val acquirer = object : LeaderLeaseAcquirer {
            override val configuredOptions = LeaderElectionOptions(
                waitTime = 10.milliseconds,
                leaseTime = 1.seconds,
                nodeId = "node",
            )

            override fun tryAcquire(lockName: String): LeaderLeaseHandle? {
                acquireCount.incrementAndGet()
                return handle
            }

            override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? {
                acquireCount.incrementAndGet()
                return handle
            }
        }
        val lifetimeScheduler = ImmediateLifetimeScheduler()
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = acquirer,
            suspendAcquirer = null,
            properties = LeaderRouteLeaseProperties(drainTimeout = java.time.Duration.ofMillis(100)),
            lifetimeScheduler = lifetimeScheduler,
        )

        runtime.tryAcquire(LeaderSlot("shutdown-race", "node")).shouldBeNull()
        acquireCount.get() shouldBeEqualTo 1
        await.atMost(5.seconds).untilAsserted {
            releaseCount.get() shouldBeEqualTo 1
        }
        runtime.close()
        releaseCount.get() shouldBeEqualTo 1
        lifetimeScheduler.shutdownNow()
    }

    @Test
    fun `suspend lifetime callback publishes terminal state before returning`() = runSuspendIO {
        val releaseCount = AtomicInteger()
        val handle = object : SuspendLeaderLeaseHandle {
            override val lockName: String = "suspend-shutdown-race"
            override val auditLeaderId: String = "node"
            override val acquiredAt: Instant = Instant.now()
            override suspend fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome =
                ExtendOutcome.Extended(Instant.now())
            override suspend fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD
            override suspend fun isStillHeld(): Boolean = true
            override suspend fun release() {
                releaseCount.incrementAndGet()
            }
        }
        val acquirer = object : SuspendLeaderLeaseAcquirer {
            override val configuredOptions = LeaderElectionOptions(
                waitTime = 10.milliseconds,
                leaseTime = 1.seconds,
                nodeId = "node",
            )

            override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle = handle

            override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle = handle
        }
        val lifetimeScheduler = ImmediateLifetimeScheduler()
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = LocalLeaderElector(
                LeaderElectionOptions(
                    waitTime = 10.milliseconds,
                    leaseTime = 1.seconds,
                    nodeId = "blocking-node",
                ),
            ),
            suspendAcquirer = acquirer,
            properties = LeaderRouteLeaseProperties(drainTimeout = java.time.Duration.ofMillis(100)),
            lifetimeScheduler = lifetimeScheduler,
        )

        runtime.tryAcquireSuspend(LeaderSlot("suspend-shutdown-race", "node")).shouldBeNull()
        await.atMost(5.seconds).untilAsserted {
            releaseCount.get() shouldBeEqualTo 1
        }
        runtime.close()
        releaseCount.get() shouldBeEqualTo 1
        lifetimeScheduler.shutdownNow()
    }

    private class ImmediateLifetimeScheduler(
        private val delegate: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    ) : ScheduledExecutorService by delegate {
        override fun schedule(command: Runnable, delay: Long, unit: java.util.concurrent.TimeUnit): ScheduledFuture<*> {
            return if (unit.toNanos(delay) >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) {
                command.run()
                CompletedScheduledFuture(Unit)
            } else {
                delegate.schedule(command, delay, unit)
            }
        }

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: java.util.concurrent.TimeUnit): ScheduledFuture<V> {
            return if (unit.toNanos(delay) >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) {
                CompletedScheduledFuture(callable.call())
            } else {
                delegate.schedule(callable, delay, unit)
            }
        }
    }

    private class CompletedScheduledFuture<V>(private val value: V) : ScheduledFuture<V> {
        override fun getDelay(unit: java.util.concurrent.TimeUnit): Long = 0
        override fun compareTo(other: Delayed): Int = 0
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): V = value
        override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): V = value
    }
}

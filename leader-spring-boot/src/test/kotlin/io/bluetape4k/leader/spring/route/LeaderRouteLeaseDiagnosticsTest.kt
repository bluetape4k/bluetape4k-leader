package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.milliseconds

class LeaderRouteLeaseDiagnosticsTest {

    @Test
    fun `diagnostics exposes only bounded aggregate and fixed observations`() {
        val sinkCodes = mutableListOf<LeaseObservationCode>()
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = LocalLeaderElector(LeaderElectionOptions(waitTime = 1.milliseconds)),
            suspendAcquirer = null,
            properties = LeaderRouteLeaseProperties(maxBlockingWaitTime = java.time.Duration.ofMillis(20)),
            externalObservationSink = SanitizedRouteLeaseObservationSink { sinkCodes += it },
        )

        runtime.tryAcquire(LeaderSlot("private-lock-name", "private-leader-id"))!!.release()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runtime.activeLeases != 0 && System.nanoTime() < deadline) Thread.sleep(2)
        runtime.activeLeases shouldBeEqualTo 0

        val diagnostics = LeaderRouteLeaseDiagnosticsContributor(runtime).diagnostics()
        diagnostics.runtimeState shouldBeEqualTo "RUNNING"
        diagnostics.active shouldBeEqualTo 0
        diagnostics.effectiveActiveCapacity shouldBeEqualTo 1_024
        diagnostics.observations.keys shouldBeEqualTo LeaseObservationCode.entries.map { it.name }.toSet()
        diagnostics.observations.keys.none { it.contains("private", ignoreCase = true) }.shouldBeTrue()
        sinkCodes.none { it == LeaseObservationCode.BACKEND_ERROR }.shouldBeTrue()
        runtime.close()
    }

    @Test
    fun `hard lease lifetime releases a handle and leaves no active reservation`() {
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = LocalLeaderElector(LeaderElectionOptions(waitTime = 20.milliseconds)),
            suspendAcquirer = null,
            properties = LeaderRouteLeaseProperties(
                maxBlockingWaitTime = java.time.Duration.ofMillis(20),
                maxLeaseLifetime = java.time.Duration.ofMillis(30),
            ),
        )

        runtime.tryAcquire(LeaderSlot("lifetime-lock", "node"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runtime.activeLeases != 0 && System.nanoTime() < deadline) Thread.sleep(2)

        runtime.activeLeases shouldBeEqualTo 0
        runtime.residualLeases shouldBeEqualTo 0
        runtime.diagnostics().observations[LeaseObservationCode.TIMEOUT.name]!! shouldBeEqualTo 1
        runtime.close()
    }

    @Test
    fun `cleanup timeout transfers the held reservation to residual registry`() {
        val runtime = LeaderRouteLeaseRuntime(
            acquirer = BlockingReleaseAcquirer(),
            suspendAcquirer = null,
            properties = LeaderRouteLeaseProperties(
                maxBlockingWaitTime = java.time.Duration.ofMillis(20),
                drainTimeout = java.time.Duration.ofMillis(40),
            ),
        )
        runtime.tryAcquire(LeaderSlot("cleanup-timeout", "node"))!!.release()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            runtime.diagnostics().observations[LeaseObservationCode.CLEANUP_TIMEOUT.name] == 0L &&
            System.nanoTime() < deadline
        ) Thread.sleep(2)

        runtime.residualLeases shouldBeEqualTo 1
        runtime.diagnostics().observations[LeaseObservationCode.CLEANUP_TIMEOUT.name] shouldBeEqualTo 1
        runtime.close()
    }

    private class BlockingReleaseAcquirer : LeaderLeaseAcquirer {
        override val configuredOptions: LeaderElectionOptions = LeaderElectionOptions.Default

        override fun tryAcquire(lockName: String): LeaderLeaseHandle? = tryAcquire(
            LeaderSlot(lockName, configuredOptions.nodeId),
        )

        override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle = object : LeaderLeaseHandle {
            override val lockName: String = slot.lockName
            override val auditLeaderId: String = slot.leaderId
            override val acquiredAt: Instant = Instant.now()

            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.Rejected

            override fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD

            override fun isStillHeld(): Boolean = true

            override fun release() {
                Thread.sleep(200)
            }
        }
    }
}

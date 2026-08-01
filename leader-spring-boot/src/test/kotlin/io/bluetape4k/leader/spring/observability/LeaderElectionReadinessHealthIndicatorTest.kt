package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.spring.properties.LeaderObservabilityHealthProperties
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LeaderElectionReadinessHealthIndicatorTest {

    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val warningThreshold = Duration.ofSeconds(10)
    private val elector = mockk<LeaderElector>()

    @BeforeEach
    fun clearElector() {
        clearMocks(elector)
        every { elector.supportsAuditLeaderState } returns true
    }

    @Test
    fun `empty registry is UP with zero details`() {
        val health = indicator().health()

        health.status shouldBeEqualTo Status.UP
        health.details shouldBeEqualTo mapOf(
            "backend" to "unknown",
            "stateProviderBean" to "",
            "stateSupported" to true,
            "knownLocks" to 0,
            "occupiedLocks" to 0,
            "unknownLeaseExpiry" to 0,
            "expiringLeases" to 0,
            "expiringLockNames" to emptyList<String>(),
            "failedLockNames" to emptyList<String>(),
        )
    }

    @Test
    fun `unsupported state provider is UNKNOWN instead of false UP`() {
        val health = LeaderElectionReadinessHealthIndicator(
            leaderElector = DefaultStateLeaderElector(),
            registry = LeaderElectionStatusRegistry(listOf("unsupported-job")),
            leaseWarningThreshold = warningThreshold,
            clock = clock,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
    }

    @Test
    fun `occupied lease beyond warning threshold is UP`() {
        every { elector.state("healthy-job") } returns occupied("healthy-job", now.plusSeconds(60))

        val health = indicator("healthy-job").health()

        health.status shouldBeEqualTo Status.UP
        health.details["knownLocks"] shouldBeEqualTo 1
        health.details["occupiedLocks"] shouldBeEqualTo 1
        health.details["expiringLeases"] shouldBeEqualTo 0
        verify(exactly = 1) { elector.state("healthy-job") }
    }

    @Test
    fun `lease at warning boundary is OUT_OF_SERVICE`() {
        every { elector.state("expiring-job") } returns occupied("expiring-job", now.plus(warningThreshold))

        val health = indicator("expiring-job").health()

        health.status shouldBeEqualTo Status.OUT_OF_SERVICE
        health.details["expiringLeases"] shouldBeEqualTo 1
        health.details["expiringLockNames"] shouldBeEqualTo listOf("expiring-job")
    }

    @Test
    fun `occupied lease with unknown expiry stays UP and is reported`() {
        every { elector.state("unknown-job") } returns LeaderState.occupied(
            "unknown-job",
            LeaderLease(auditLeaderId = "node-1"),
        )

        val health = indicator("unknown-job").health()

        health.status shouldBeEqualTo Status.UP
        health.details["unknownLeaseExpiry"] shouldBeEqualTo 1
        health.details["expiringLeases"] shouldBeEqualTo 0
    }

    @Test
    fun `known lock state failure is DOWN without hiding other details`() {
        every { elector.state("healthy-job") } returns LeaderState.empty("healthy-job")
        every { elector.state("unavailable-job") } throws IllegalStateException("backend unavailable")

        val health = indicator("unavailable-job", "healthy-job").health()

        health.status shouldBeEqualTo Status.DOWN
        health.details["knownLocks"] shouldBeEqualTo 2
        health.details["failedLockNames"] shouldBeEqualTo listOf("unavailable-job")
        health.details.toString().contains("backend unavailable").shouldBeFalse()
        verify(exactly = 1) { elector.state("healthy-job") }
        verify(exactly = 1) { elector.state("unavailable-job") }
    }

    @Test
    fun `negative warning threshold is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderObservabilityHealthProperties(leaseWarningThreshold = Duration.ofSeconds(-1))
        }
    }

    private fun indicator(vararg lockNames: String): LeaderElectionReadinessHealthIndicator =
        LeaderElectionReadinessHealthIndicator(
            leaderElector = elector,
            registry = LeaderElectionStatusRegistry(lockNames.asList()),
            leaseWarningThreshold = warningThreshold,
            clock = clock,
        )

    private fun occupied(lockName: String, leaseUntil: Instant): LeaderState =
        LeaderState.occupied(
            lockName,
            LeaderLease(auditLeaderId = "node-1", leaseUntil = leaseUntil),
        )

    private class DefaultStateLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }
}

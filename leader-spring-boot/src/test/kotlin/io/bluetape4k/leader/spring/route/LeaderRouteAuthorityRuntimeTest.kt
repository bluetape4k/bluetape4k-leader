package io.bluetape4k.leader.spring.route

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

class LeaderRouteAuthorityRuntimeTest {

    companion object: KLogging()

    private val now = Instant.parse("2026-08-23T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val slot = LeaderSlot("orders-route", "node-a")

    private val elector = mockk<LeaderElector>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(elector)
    }

    @Test
    fun `custom authority evaluation carries null state and fixed timestamp`() {
        val authority = LeaderRouteAuthority { LeaderRouteDecision.NotLeader }
        val runtime = LeaderRouteAuthorityRuntime(authority, clock)

        runtime.evaluateSnapshot(slot) shouldBeEqualTo LeaderRouteEvaluation(
            LeaderRouteDecision.NotLeader,
            null,
            now,
        )
    }

    @Test
    fun `state authority evaluation reads one snapshot and shares timestamp`() {
        every { elector.supportsAuditLeaderState } returns true
        every { elector.state(slot.lockName) } returns LeaderState.occupied(
            slot.lockName,
            LeaderLease("node-b", now, now + 30.seconds()),
        )

        val runtime = LeaderRouteAuthorityRuntime(StateLeaderRouteAuthority(elector), clock)

        runtime.evaluateSnapshot(slot) shouldBeEqualTo LeaderRouteEvaluation(
            LeaderRouteDecision.NotLeader,
            LeaderState.occupied(slot.lockName, LeaderLease("node-b", now, now + 30.seconds())),
            now,
        )
        verify(exactly = 1) { elector.state(slot.lockName) }
    }

    @Test
    fun `state timestamp is captured after lookup for redirect freshness`() {
        val advancingClock = AdvancingClock(now)

        every { elector.supportsAuditLeaderState } returns true
        every { elector.state(slot.lockName) } answers {
            advancingClock.advanceTo(now + 20.seconds())
            LeaderState.occupied(
                slot.lockName,
                LeaderLease("node-b", leaseUntil = now + 10.seconds()),
            )
        }

        val runtime = LeaderRouteAuthorityRuntime(StateLeaderRouteAuthority(elector), advancingClock)

        val evaluation = runtime.evaluateSnapshot(slot)
        log.debug { "evaluation=$evaluation" }
        evaluation.evaluatedAt shouldBeEqualTo now + 20.seconds()

        var resolverCalls = 0
        LeaderRouteRedirectPolicy(LeaderRouteRedirectProperties(enabled = true))
            .redirect(
                slot,
                evaluation,
                {
                    resolverCalls++
                    java.net.URI("/leader/orders")
                },
                metadata = null,
                framework = LeaderRouteRedirectFramework.MVC,
            )
            .shouldBeNull()

        resolverCalls shouldBeEqualTo 0
    }

    @Test
    fun `ordinary custom authority failure becomes unavailable`() {
        val runtime = LeaderRouteAuthorityRuntime({ error("backend secret") }, clock)

        runtime.evaluateSnapshot(slot).decision shouldBeEqualTo LeaderRouteDecision.Unavailable
    }

    @Test
    fun `cancellation remains observable`() {
        val runtime = LeaderRouteAuthorityRuntime(
            { throw CancellationException("cancelled") },
            clock,
        )

        assertFailsWith<CancellationException> {
            runtime.evaluateSnapshot(slot)
        }
    }

    private class AdvancingClock(private var current: Instant): Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceTo(next: Instant) {
            current = next
        }

        override fun toString(): String {
            return ToStringBuilder(this)
                .add("current", current)
                .toString()
        }
    }
}

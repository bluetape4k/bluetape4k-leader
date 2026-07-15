package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.local.LocalLeaderElector
import io.mockk.confirmVerified
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class StateLeaderRouteAuthorityTest {

    private val elector = mockk<LeaderElector> {
        every { supportsAuditLeaderState } returns true
    }
    private val slot = LeaderSlot("orders-route", "node-a")
    private val authority = StateLeaderRouteAuthority(elector)

    @BeforeEach
    fun resetMock() {
        clearMocks(elector)
    }

    @Test
    fun `matching audit leader id is allowed with one state read`() {
        every { elector.state(slot.lockName) } returns occupied("node-a")

        authority.evaluate(slot) shouldBeEqualTo LeaderRouteDecision.Allowed

        verify(exactly = 1) { elector.state(slot.lockName) }
        confirmVerified(elector)
    }

    @Test
    fun `empty state is not leader`() {
        every { elector.state(slot.lockName) } returns LeaderState.empty(slot.lockName)

        authority.evaluate(slot) shouldBeEqualTo LeaderRouteDecision.NotLeader

        verify(exactly = 1) { elector.state(slot.lockName) }
        confirmVerified(elector)
    }

    @Test
    fun `different audit leader id is not leader`() {
        every { elector.state(slot.lockName) } returns occupied("node-b")

        authority.evaluate(slot) shouldBeEqualTo LeaderRouteDecision.NotLeader

        verify(exactly = 1) { elector.state(slot.lockName) }
        confirmVerified(elector)
    }

    @Test
    fun `state read failure is unavailable without exposing failure`() {
        every { elector.state(slot.lockName) } throws IllegalStateException("redis password=secret")

        authority.evaluate(slot) shouldBeEqualTo LeaderRouteDecision.Unavailable

        verify(exactly = 1) { elector.state(slot.lockName) }
        confirmVerified(elector)
    }

    @Test
    fun `cancellation is preserved`() {
        every { elector.state(slot.lockName) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { authority.evaluate(slot) }

        verify(exactly = 1) { elector.state(slot.lockName) }
        confirmVerified(elector)
    }

    @Test
    fun `local elector exposes matching audit identity only while slot is held`() {
        val localElector = LocalLeaderElector()
        val localAuthority = StateLeaderRouteAuthority(localElector)

        val decisionWhileHeld = localElector.runIfLeader(slot) {
            localAuthority.evaluate(slot)
        }

        decisionWhileHeld shouldBeEqualTo LeaderRouteDecision.Allowed
        localAuthority.evaluate(slot) shouldBeEqualTo LeaderRouteDecision.NotLeader
    }

    @Test
    fun `unsupported state elector is rejected at the authority boundary`() {
        val unsupported = mockk<LeaderElector> {
            every { supportsAuditLeaderState } returns false
        }

        val failure = assertFailsWith<LeaderRouteGuardConfigurationException> {
            StateLeaderRouteAuthority(unsupported)
        }

        failure.code shouldBeEqualTo LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED
    }

    private fun occupied(auditLeaderId: String): LeaderState =
        LeaderState.occupied(slot.lockName, LeaderLease(auditLeaderId))
}

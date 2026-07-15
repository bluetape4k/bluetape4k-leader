package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CancellationException

/**
 * Default read-only authority backed by one [LeaderElector] state snapshot.
 *
 * The elector must declare [LeaderElector.supportsAuditLeaderState]. The
 * guarded [LeaderSlot.leaderId] must be unique to one live process incarnation
 * and reused by that process for both election and route guarding. Reusing an
 * identity across restarts can match a stale lease left by the previous process.
 *
 * A request is allowed only when the snapshot is occupied and its audit leader
 * ID equals [LeaderSlot.leaderId]. Occupancy alone never proves local ownership.
 *
 * @throws LeaderRouteGuardConfigurationException when [elector] cannot expose
 * audit leader identity through its state snapshot.
 */
class StateLeaderRouteAuthority(
    private val elector: LeaderElector,
) : LeaderRouteAuthority {

    init {
        if (!elector.supportsAuditLeaderState) {
            throw LeaderRouteGuardConfigurationException(
                LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED,
                "StateLeaderRouteAuthority requires a LeaderElector that exposes audit leader identity",
            )
        }
    }

    override fun evaluate(slot: LeaderSlot): LeaderRouteDecision =
        try {
            val state = elector.state(slot.lockName)
            if (state.isOccupied && state.leader?.auditLeaderId == slot.leaderId) {
                LeaderRouteDecision.Allowed
            } else {
                LeaderRouteDecision.NotLeader
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            LeaderRouteDecision.Unavailable
        }
}

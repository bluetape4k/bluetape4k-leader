package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot

/** Internal effective authority selected after exclusive-mode validation. */
internal class LeaderRouteAuthorityRuntime(
    internal val authority: LeaderRouteAuthority,
) {
    fun evaluate(slot: LeaderSlot): LeaderRouteDecision {
        val decision: LeaderRouteDecision? = authority.evaluate(slot)
        return decision ?: LeaderRouteDecision.Unavailable
    }
}

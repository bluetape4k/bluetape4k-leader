package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot

/**
 * Makes a bounded, side-effect-free decision for one leader-gated route.
 *
 * Implementations must not acquire, extend, or release leader leases. Every
 * result other than [LeaderRouteDecision.Allowed] is fail-closed. Java
 * implementations that violate the non-null return contract are normalized to
 * [LeaderRouteDecision.Unavailable].
 */
fun interface LeaderRouteAuthority {

    /** Evaluates whether the local application may serve the route guarded by [slot]. */
    fun evaluate(slot: LeaderSlot): LeaderRouteDecision
}

package io.bluetape4k.leader.spring.route;

import io.bluetape4k.leader.LeaderSlot;

/** Java regression fixture that violates the non-null SPI contract. */
public final class NullLeaderRouteAuthority implements LeaderRouteAuthority {

    @Override
    public LeaderRouteDecision evaluate(LeaderSlot slot) {
        return null;
    }
}

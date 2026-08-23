package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderState
import java.time.Instant

internal data class LeaderRouteEvaluation(
    val decision: LeaderRouteDecision,
    val leaderState: LeaderState?,
    val evaluatedAt: Instant,
)

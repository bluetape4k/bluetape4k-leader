package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * resolver에 전달되는 한 번의 leader route 평가 문맥입니다.
 *
 * `LeaderState`는 built-in `STATE` authority가 이미 읽은 값일 때만 전달되며,
 * `CUSTOM` authority에서는 `null`일 수 있습니다.
 */
data class LeaderRouteRedirectContext(
    val slot: LeaderSlot,
    val leaderState: LeaderState?,
    val evaluatedAt: Instant,
    val leaseSafetyWindow: Duration,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

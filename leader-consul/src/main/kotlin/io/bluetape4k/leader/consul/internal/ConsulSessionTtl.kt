package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.support.requireInRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object ConsulSessionTtl {

    fun ttlSeconds(leaseTime: Duration): Long {
        leaseTime.requireInRange(
            ConsulLeaderElectionOptions.MinLeaseTime,
            ConsulLeaderElectionOptions.MaxLeaseTime,
            "leaseTime"
        )
        return leaseTime.inWholeSeconds.coerceAtLeast(ConsulLeaderElectionOptions.MinLeaseTime.inWholeSeconds)
    }

    fun renewDelay(leaseTime: Duration): Duration {
        ttlSeconds(leaseTime)
        val earlyRenew = minOf(leaseTime / 3, leaseTime - 2.seconds)
        return earlyRenew.coerceAtLeast(1.seconds)
    }
}

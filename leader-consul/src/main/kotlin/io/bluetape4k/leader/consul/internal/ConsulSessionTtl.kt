package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object ConsulSessionTtl {

    fun ttlSeconds(leaseTime: Duration): Long {
        require(leaseTime >= ConsulLeaderElectionOptions.MinLeaseTime) {
            "leaseTime must be at least ${ConsulLeaderElectionOptions.MinLeaseTime}. leaseTime=$leaseTime"
        }
        require(leaseTime <= ConsulLeaderElectionOptions.MaxLeaseTime) {
            "leaseTime must be at most ${ConsulLeaderElectionOptions.MaxLeaseTime}. leaseTime=$leaseTime"
        }
        return leaseTime.inWholeSeconds.coerceAtLeast(ConsulLeaderElectionOptions.MinLeaseTime.inWholeSeconds)
    }

    fun renewDelay(leaseTime: Duration): Duration {
        ttlSeconds(leaseTime)
        val earlyRenew = minOf(leaseTime / 3, leaseTime - 2.seconds)
        return earlyRenew.coerceAtLeast(1.seconds)
    }
}

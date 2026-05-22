package io.bluetape4k.leader.consul

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.internal.ConsulLeaderPaths
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Options for Consul-backed single-leader election.
 *
 * ## Behavior / Contract
 * - [leaderOptions] controls wait time, lease duration, node identity, minimum lease time, and auto-extension.
 * - Consul Session TTL requires `leaderOptions.leaseTime` in the range `[10.seconds, 86_400.seconds]`.
 * - [lockDelay] defaults to zero to keep scheduler-style skip/reacquire behavior predictable.
 * - A zero lock delay can overlap an old holder that is still running after TTL expiry; use idempotent actions or fencing
 *   tokens when duplicate execution is unsafe.
 * - Consul endpoint, ACL token, datacenter, and agent lifecycle are caller-owned through [ConsulEndpoint].
 */
data class ConsulLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
) : Serializable {

    init {
        ConsulLeaderPaths.validatePrefix(keyPrefix)
        require(sessionNamePrefix.isNotBlank()) {
            "sessionNamePrefix must not be blank."
        }
        require(leaderOptions.leaseTime >= MinLeaseTime) {
            "leaderOptions.leaseTime must be at least $MinLeaseTime for Consul Session TTL. " +
                "leaseTime=${leaderOptions.leaseTime}"
        }
        require(leaderOptions.leaseTime <= MaxLeaseTime) {
            "leaderOptions.leaseTime must be at most $MaxLeaseTime for Consul Session TTL. " +
                "leaseTime=${leaderOptions.leaseTime}"
        }
        require(lockDelay >= Duration.ZERO) {
            "lockDelay must be non-negative. lockDelay=$lockDelay"
        }
    }

    companion object {
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"

        val MinLeaseTime: Duration = 10.seconds

        val MaxLeaseTime: Duration = 86_400.seconds

        @JvmField
        val Default = ConsulLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

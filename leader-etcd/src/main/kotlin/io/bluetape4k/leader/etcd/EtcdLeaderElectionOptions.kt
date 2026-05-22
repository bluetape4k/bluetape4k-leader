package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Options for etcd-backed single-leader election.
 *
 * ## Behavior / Contract
 * - [leaderOptions] controls wait time, lease duration, node identity, minimum lease time, and auto-extension.
 * - [keyPrefix] is the absolute etcd key prefix used for leader lock keys.
 * - [retryDelay] is reserved for retrying APIs that do not use jetcd's queued Lock service directly.
 * - Authentication, TLS, endpoints, and client lifecycle are caller-owned through the supplied jetcd `Client`.
 *
 * ```kotlin
 * val options = EtcdLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(leaseTime = 30.seconds),
 *     keyPrefix = "/apps/orders/leader",
 * )
 * ```
 */
data class EtcdLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    init {
        EtcdLeaderPaths(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * Default options instance using `/bluetape4k/leader` and [LeaderElectionOptions.Default].
         */
        @JvmField
        val Default = EtcdLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

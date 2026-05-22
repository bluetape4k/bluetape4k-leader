package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Options for etcd-backed multi-leader group election.
 *
 * ## Behavior / Contract
 * - [leaderGroupOptions] controls slot count, wait time, lease duration, node identity, and minimum lease time.
 * - [keyPrefix] is the absolute etcd key prefix used for group slot lock keys.
 * - [retryDelay] is reserved for retrying APIs that do not use jetcd's queued Lock service directly.
 * - Authentication, TLS, endpoints, and client lifecycle are caller-owned through the supplied jetcd `Client`.
 */
data class EtcdLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    /** Maximum number of concurrent leaders allowed. */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        EtcdLeaderPaths(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * Default options instance using `/bluetape4k/leader` and [LeaderGroupElectionOptions.Default].
         */
        @JvmField
        val Default = EtcdLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}

package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import java.io.Serializable

/**
 * Options for single-leader election backed by Exposed R2DBC.
 *
 * ```kotlin
 * val options = ExposedR2dbcLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(
 *         waitTime = Duration.ofSeconds(3),
 *         leaseTime = Duration.ofSeconds(30),
 *     ),
 *     retryStrategy = RetryStrategy.Jitter(baseDelayMs = 100L),
 *     recordHistory = true,
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedR2dbcSuspendLeaderElector(db, options)
 * ```
 *
 * @property leaderOptions Single-leader election options (waitTime, leaseTime)
 * @property retryStrategy Lock acquisition retry strategy. Defaults to [RetryStrategy.Jitter]
 * @property recordHistory When `true`, records acquire/complete/fail history in [io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable]
 * @property lockOwner Lock owner identifier. Must be within [ExposedLeaderConstants.LOCK_OWNER_LENGTH] characters. Not recorded if `null`
 */
data class ExposedR2dbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {

    init {
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /**
         * Default options instance.
         *
         * - leaderOptions = [LeaderElectionOptions.Default] (waitTime/leaseTime use leader-core defaults)
         * - retryStrategy = [RetryStrategy.Jitter] (baseDelayMs = 50ms)
         * - recordHistory = `false`
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedR2dbcLeaderElectionOptions()
    }
}

package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import java.io.Serializable

/**
 * Options for single-leader election backed by Exposed JDBC.
 *
 * ```kotlin
 * val options = ExposedJdbcLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(
 *         waitTime = Duration.ofSeconds(3),
 *         leaseTime = Duration.ofSeconds(30),
 *     ),
 *     retryStrategy = RetryStrategy.Jitter(baseDelayMs = 100L),
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedJdbcLeaderElector(db, options)
 * ```
 *
 * @property leaderOptions Single-leader election options (waitTime, leaseTime)
 * @property retryStrategy Lock acquisition retry strategy. Defaults to [RetryStrategy.Jitter]
 * @property lockOwner Lock owner identifier. Must be within [ExposedLeaderConstants.LOCK_OWNER_LENGTH] characters. Not recorded if `null`
 */
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
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
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

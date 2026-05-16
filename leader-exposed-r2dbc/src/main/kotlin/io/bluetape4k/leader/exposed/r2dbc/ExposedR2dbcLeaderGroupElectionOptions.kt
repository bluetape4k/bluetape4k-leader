package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * Options for multi-leader group election backed by Exposed R2DBC.
 *
 * ```kotlin
 * val options = ExposedR2dbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     retryStrategy = RetryStrategy.Exponential(),
 *     recordHistory = true,
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedR2dbcSuspendLeaderGroupElector(db, options)
 * ```
 *
 * @property leaderGroupOptions Group leader election options (maxLeaders, waitTime, leaseTime). `maxLeaders` must be positive
 * @property retryStrategy Lock acquisition retry strategy. Defaults to [RetryStrategy.Jitter]
 * @property recordHistory When `true`, records acquire/complete/fail history
 * @property lockOwner Lock owner identifier. Must be within [ExposedLeaderConstants.LOCK_OWNER_LENGTH] characters. Not recorded if `null`
 */
data class ExposedR2dbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {

    /** Maximum number of concurrent leaders allowed (delegates to [LeaderGroupElectionOptions.maxLeaders]). */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /**
         * Default options instance.
         *
         * - leaderGroupOptions = [LeaderGroupElectionOptions.Default]
         * - retryStrategy = [RetryStrategy.Jitter]
         * - recordHistory = `false`
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedR2dbcLeaderGroupElectionOptions()
    }
}

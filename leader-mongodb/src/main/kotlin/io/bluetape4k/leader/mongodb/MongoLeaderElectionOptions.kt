package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Options data class for MongoDB-based leader election.
 *
 * ```kotlin
 * val options = MongoLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(
 *         waitTime = 3.seconds,
 *         leaseTime = 30.seconds,
 *     ),
 *     retryDelay = 100.milliseconds,
 * )
 * val election = MongoLeaderElector(collection, options)
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 *
 * @property leaderOptions Single-leader election options (waitTime, leaseTime)
 * @property retryDelay Upper bound for full jitter applied on lock acquisition retry (`[1ms, retryDelay)` uniform distribution). Defaults to 50ms
 */
data class MongoLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {
    init {
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * Default options instance (`waitTime=5s`, `leaseTime=60s`, `retryDelay=50ms`).
         */
        @JvmField
        val Default = MongoLeaderElectionOptions()
    }
}

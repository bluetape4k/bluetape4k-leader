package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Options data class for MongoDB-based multi-leader group election.
 *
 * ```kotlin
 * val options = MongoLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     retryDelay = 100.milliseconds,
 * )
 * val election = MongoLeaderGroupElector(groupCollection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * // result == processChunk() return value (slot acquired) or null (no slot available)
 * ```
 *
 * @property leaderGroupOptions Group leader election options (maxLeaders, waitTime, leaseTime)
 * @property retryDelay Upper bound for full jitter applied on lock acquisition retry (`[1ms, retryDelay)` uniform distribution). Defaults to 50ms
 */
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    /** Maximum number of concurrent leaders allowed (delegates to [LeaderGroupElectionOptions.maxLeaders]). */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * Default options instance (`maxLeaders=2`, `waitTime=5s`, `leaseTime=60s`, `retryDelay=50ms`).
         */
        @JvmField
        val Default = MongoLeaderGroupElectionOptions()
    }
}

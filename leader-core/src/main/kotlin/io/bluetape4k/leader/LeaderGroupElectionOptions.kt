package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Options data class used for multi-leader group election.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(
 *     maxLeaders = 3,
 *     waitTime = 3.seconds,
 *     leaseTime = 30.seconds,
 * )
 * val election = LocalLeaderGroupElector(options)
 * val result = election.runIfLeader("batch-job") { "done" }
 * // result == "done"
 * ```
 *
 * @property maxLeaders maximum number of concurrent leaders allowed. Default is 2.
 * @property waitTime maximum wait time to acquire a leader slot. Default is 5 seconds.
 * @property leaseTime maximum lease duration for holding a leader slot. Default is 60 seconds.
 * @property nodeId node identifier exposed in state queries. Default is a stable JVM-process-level id.
 * @property minLeaseTime minimum time to hold a leader group slot even if the action finishes early. Default is 0 seconds.
 */
data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
): Serializable {

    init {
        maxLeaders.requireGe(1, "maxLeaders")
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        nodeId.requireNotBlank("nodeId")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        require(minLeaseTime <= leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * Default options instance (`maxLeaders=2`, `waitTime=5s`, `leaseTime=60s`).
         *
         * ```kotlin
         * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions.Default)
         * ```
         */
        @JvmField
        val Default = LeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}

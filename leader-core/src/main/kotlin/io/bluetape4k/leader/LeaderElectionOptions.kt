package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Options data class used for leader election.
 *
 * ```kotlin
 * val options = LeaderElectionOptions(
 *     waitTime = 3.seconds,
 *     leaseTime = 30.seconds,
 * )
 * val election = LocalLeaderElector(options)
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 *
 * @property waitTime maximum wait time to acquire the leader lock. Default is 5 seconds.
 * @property leaseTime maximum lease duration for holding leadership. Default is 60 seconds.
 * @property nodeId node identifier exposed in state queries. Default is a stable JVM-process-level id.
 * @property minLeaseTime minimum time to hold the leader lease even if the action finishes early. Default is 0 seconds.
 * @property autoExtend whether to periodically extend the lease while the action is running. Default is false.
 */
data class LeaderElectionOptions(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
    val autoExtend: Boolean = false,
): Serializable {
    init {
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        nodeId.requireNotBlank("nodeId")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        require(minLeaseTime <= leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * Default options instance (`waitTime=5s`, `leaseTime=60s`).
         *
         * ```kotlin
         * val election = LocalLeaderElector(LeaderElectionOptions.Default)
         * ```
         */
        @JvmField
        val Default = LeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

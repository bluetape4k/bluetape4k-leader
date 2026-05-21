package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Dispatch scheduler guarded by Redis leader election.
 *
 * ## Contract
 *
 * Nodes sharing the same [lockName] race for leadership. Exactly one holder can
 * run [schedule] while the lease is active; non-leaders return an empty
 * scheduled-item list and [RateLimiterDemoStatus.REJECTED] for dispatch.
 */
class LeaderDispatchScheduler(
    val nodeId: String,
    connection: StatefulRedisConnection<String, String>,
    private val lockName: String,
    waitTime: Duration = 500.milliseconds,
    leaseTime: Duration = 10.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging()

    private val elector = LettuceLeaderElector(
        connection,
        LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime),
    )

    fun schedule(workSupplier: () -> List<String>): DispatchReport {
        val scheduledItems = elector.runIfLeader(lockName) {
            log.info { "[$nodeId] SCHEDULED dispatch under lock=$lockName" }
            workSupplier()
        }

        return if (scheduledItems == null) {
            log.info { "[$nodeId] REJECTED dispatch because another node is leader" }
            DispatchReport(
                nodeId = nodeId,
                status = RateLimiterDemoStatus.REJECTED,
                scheduledItems = emptyList(),
            )
        } else {
            DispatchReport(
                nodeId = nodeId,
                status = RateLimiterDemoStatus.SCHEDULED,
                scheduledItems = scheduledItems,
            )
        }
    }
}

data class DispatchReport(
    val nodeId: String,
    val status: RateLimiterDemoStatus,
    val scheduledItems: List<String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 7331738806090205222L
    }
}

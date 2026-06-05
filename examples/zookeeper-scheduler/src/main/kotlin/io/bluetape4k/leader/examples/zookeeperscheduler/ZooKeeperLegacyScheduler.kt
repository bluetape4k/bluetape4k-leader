package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.apache.curator.framework.CuratorFramework
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a legacy scheduled job only on the node elected through ZooKeeper.
 *
 * ## Behavior / Contract
 *
 * - Instances sharing the same [ZooKeeperSchedulerConfig.lockName] compete through `ZooKeeperLeaderElector`.
 * - The elected instance executes the supplied job and returns [SchedulerRunStatus.EXECUTED].
 * - Non-leaders return [SchedulerRunStatus.SKIPPED] without executing the supplied job.
 * - ZooKeeper locks are session-based; this example keeps `autoExtend=false`.
 */
class ZooKeeperLegacyScheduler(
    private val config: ZooKeeperSchedulerConfig,
    curator: CuratorFramework,
) {

    companion object: KLogging()

    private val elector = ZooKeeperLeaderElector(
        client = curator,
        basePath = config.basePath.value,
        options = LeaderElectionOptions(
            nodeId = config.nodeId.value,
            waitTime = config.waitTime,
            leaseTime = config.leaseTime,
            autoExtend = false,
        ),
    )

    /**
     * Executes [job] only when this node acquires the ZooKeeper scheduler lock.
     */
    fun runOnce(
        scheduleId: SchedulerRunId,
        job: () -> List<String>,
    ): SchedulerRunReport {
        val startedAt = System.nanoTime()
        val completedSteps = elector.runIfLeader(config.lockName.value) {
            log.info { "[${config.nodeId.value}] ACQUIRED ZooKeeper leadership for ${scheduleId.value}" }
            job().also { steps ->
                steps.forEachIndexed { index, step -> step.requireNotBlank("completedSteps[$index]") }
            }
        }

        val elapsed = (System.nanoTime() - startedAt).nanoseconds
        return if (completedSteps == null) {
            log.info { "[${config.nodeId.value}] SKIPPED ${scheduleId.value} because another node is leader" }
            SchedulerRunReport(
                nodeId = config.nodeId,
                scheduleId = scheduleId,
                status = SchedulerRunStatus.SKIPPED,
                completedSteps = emptyList(),
                elapsed = elapsed,
            )
        } else {
            SchedulerRunReport(
                nodeId = config.nodeId,
                scheduleId = scheduleId,
                status = SchedulerRunStatus.EXECUTED,
                completedSteps = completedSteps,
                elapsed = elapsed,
            )
        }
    }
}

/**
 * Caller-owned configuration for one ZooKeeper-backed scheduler participant.
 */
data class ZooKeeperSchedulerConfig(
    val nodeId: SchedulerNodeId,
    val lockName: SchedulerLockName,
    val basePath: ZooKeeperSchedulerBasePath = ZooKeeperSchedulerBasePath("/bluetape4k/examples/zookeeper-scheduler"),
    val waitTime: Duration = 200.milliseconds,
    val leaseTime: Duration = 5.seconds,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Stable node identity used by the example scheduler.
 */
@JvmInline
value class SchedulerNodeId(val value: String): Serializable {
    init {
        value.requireNotBlank("nodeId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Logical lock name shared by scheduler instances that must not run concurrently.
 */
@JvmInline
value class SchedulerLockName(val value: String): Serializable {
    init {
        value.requireNotBlank("lockName")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * ZooKeeper base path for this example's leader-election znodes.
 */
@JvmInline
value class ZooKeeperSchedulerBasePath(val value: String): Serializable {
    init {
        value.requireNotBlank("basePath")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Business run id for one scheduled execution.
 */
@JvmInline
value class SchedulerRunId(val value: String): Serializable {
    init {
        value.requireNotBlank("scheduleId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Outcome of one scheduler attempt.
 */
enum class SchedulerRunStatus {
    EXECUTED,
    SKIPPED,
}

/**
 * Serializable report emitted after one scheduler attempt.
 */
data class SchedulerRunReport(
    val nodeId: SchedulerNodeId,
    val scheduleId: SchedulerRunId,
    val status: SchedulerRunStatus,
    val completedSteps: List<String>,
    val elapsed: Duration,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

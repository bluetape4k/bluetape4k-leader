package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates service maintenance so only the elected Consul leader runs the supplied work.
 *
 * ## Behavior / Contract
 *
 * - Instances sharing the same lock name and key prefix compete through one Consul KV lock.
 * - The elected instance returns [MaintenanceStatus.PERFORMED] with the completed steps.
 * - Non-leaders return [MaintenanceStatus.SKIPPED] without running the supplied work.
 * - Leadership is released after the supplier completes.
 */
class ServiceMaintenanceCoordinator(
    private val config: ServiceMaintenanceConfig,
    endpoint: ConsulEndpoint,
) {

    companion object : KLogging()

    private val elector = ConsulLeaderElector(
        endpoint = endpoint,
        options = ConsulLeaderElectionOptions(
            keyPrefix = config.keyPrefix.value,
            leaderOptions = LeaderElectionOptions(
                nodeId = config.nodeId.value,
                waitTime = config.waitTime,
                leaseTime = config.leaseTime,
                autoExtend = true,
            ),
        ),
    )

    /**
     * Runs [workSupplier] only when this node acquires the Consul maintenance lock.
     */
    fun performMaintenance(workSupplier: () -> List<String>): MaintenanceReport {
        val completedSteps = elector.runIfLeader(config.lockName.value) {
            log.info { "[${config.nodeId.value}] ACQUIRED Consul leadership for lock=${config.lockName.value}" }
            workSupplier()
        }

        return if (completedSteps == null) {
            log.info { "[${config.nodeId.value}] SKIPPED maintenance because another node is leader" }
            MaintenanceReport(
                nodeId = config.nodeId,
                status = MaintenanceStatus.SKIPPED,
                completedSteps = emptyList(),
            )
        } else {
            MaintenanceReport(
                nodeId = config.nodeId,
                status = MaintenanceStatus.PERFORMED,
                completedSteps = completedSteps,
            )
        }
    }
}

/**
 * Caller-owned configuration for one service-maintenance participant.
 */
data class ServiceMaintenanceConfig(
    val nodeId: MaintenanceNodeId,
    val lockName: MaintenanceLockName,
    val keyPrefix: MaintenanceKeyPrefix = MaintenanceKeyPrefix("bluetape4k/examples/consul-maintenance"),
    val waitTime: Duration = 500.milliseconds,
    val leaseTime: Duration = 10.seconds,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Stable identity for one service instance participating in maintenance election.
 */
@JvmInline
value class MaintenanceNodeId(val value: String) : Serializable {
    init {
        value.requireNotBlank("nodeId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Leader-election lock name for one maintenance workflow.
 */
@JvmInline
value class MaintenanceLockName(val value: String) : Serializable {
    init {
        value.requireNotBlank("lockName")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Consul KV prefix used by the example maintenance workflow.
 */
@JvmInline
value class MaintenanceKeyPrefix(val value: String) : Serializable {
    init {
        value.requireNotBlank("keyPrefix")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Outcome of one service-maintenance attempt.
 */
enum class MaintenanceStatus {
    PERFORMED,
    SKIPPED,
}

/**
 * Serializable report emitted after one maintenance attempt.
 */
data class MaintenanceReport(
    val nodeId: MaintenanceNodeId,
    val status: MaintenanceStatus,
    val completedSteps: List<String>,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

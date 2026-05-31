package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.etcd.jetcd.Client
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates a control-plane reconcile loop guarded by etcd leader election.
 *
 * ## Behavior / Contract
 *
 * - Nodes sharing the same [lockName] compete through the same etcd key prefix.
 * - Exactly one node can run [reconcile] while the etcd lease is held.
 * - Non-leaders return [ReconcileStatus.SKIPPED] without running the supplied work.
 * - After the leader body completes, the lease is released and another node can acquire the lock.
 */
class ControlPlaneReconciler(
    val nodeId: String,
    client: Client,
    private val lockName: String,
    keyPrefix: String = "/bluetape4k/examples/etcd-reconciler",
    waitTime: Duration = 500.milliseconds,
    leaseTime: Duration = 10.seconds,
) {

    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging()

    private val elector = EtcdLeaderElector(
        client = client,
        options = EtcdLeaderElectionOptions(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(
                nodeId = nodeId,
                waitTime = waitTime,
                leaseTime = leaseTime,
                autoExtend = true,
            ),
        ),
    )

    /**
     * Runs one reconcile cycle only when this node acquires leadership.
     */
    fun reconcile(workSupplier: () -> List<String>): ReconcileReport {
        val appliedResources = elector.runIfLeader(lockName) {
            log.info { "[$nodeId] ACQUIRED etcd leadership for lock=$lockName" }
            workSupplier()
        }

        return if (appliedResources == null) {
            log.info { "[$nodeId] SKIPPED reconcile because another node is leader" }
            ReconcileReport(
                nodeId = nodeId,
                status = ReconcileStatus.SKIPPED,
                appliedResources = emptyList(),
            )
        } else {
            ReconcileReport(
                nodeId = nodeId,
                status = ReconcileStatus.APPLIED,
                appliedResources = appliedResources,
            )
        }
    }
}

/**
 * Result state for a single control-plane reconcile attempt.
 */
enum class ReconcileStatus {
    APPLIED,
    SKIPPED,
}

/**
 * Serializable report emitted by [ControlPlaneReconciler].
 */
data class ReconcileReport(
    val nodeId: String,
    val status: ReconcileStatus,
    val appliedResources: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

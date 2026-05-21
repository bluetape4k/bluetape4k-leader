package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseAnnotations
import io.bluetape4k.support.requireNotBlank
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import java.time.Clock
import java.time.Instant

/**
 * Maps Kubernetes Lease snapshots into `leader-core` state values.
 *
 * ## Behavior / Contract
 * - Missing Lease, blank holder, and expired Lease map to [LeaderState.empty].
 * - Active Lease maps to [LeaderState.occupied].
 * - `LeaderLease.auditLeaderId` prefers the bluetape4k audit annotation and falls back to `spec.holderIdentity`.
 * - `LeaderLease.nodeId` comes from the bluetape4k node annotation when present.
 */
object KubernetesLeaseStateMapper {

    /**
     * Converts a Kubernetes [Lease] into a best-effort [LeaderState].
     */
    fun map(
        lockName: String,
        lease: Lease?,
        clock: Clock = Clock.systemUTC(),
    ): LeaderState {
        lockName.requireNotBlank("lockName")
        val spec = lease?.spec ?: return LeaderState.empty(lockName)
        val holder = spec.holderIdentity?.takeIf { it.isNotBlank() } ?: return LeaderState.empty(lockName)
        val leaseUntil = leaseUntil(lease) ?: return LeaderState.empty(lockName)
        if (!leaseUntil.isAfter(clock.instant())) {
            return LeaderState.empty(lockName)
        }

        val annotations = lease.metadata?.annotations.orEmpty()
        val auditLeaderId = annotations[KubernetesLeaseAnnotations.AuditLeaderId]
            ?.takeIf { it.isNotBlank() }
            ?: holder
        val nodeId = annotations[KubernetesLeaseAnnotations.NodeId]?.takeIf { it.isNotBlank() }

        return LeaderState.occupied(
            lockName,
            LeaderLease(
                auditLeaderId = auditLeaderId,
                electedAt = spec.acquireTime?.toInstant(),
                leaseUntil = leaseUntil,
                nodeId = nodeId,
            )
        )
    }

    internal fun leaseUntil(lease: Lease): Instant? {
        val spec = lease.spec ?: return null
        val renewedAt = spec.renewTime ?: spec.acquireTime ?: return null
        val seconds = spec.leaseDurationSeconds ?: return null
        return renewedAt.toInstant().plusSeconds(seconds.toLong())
    }
}

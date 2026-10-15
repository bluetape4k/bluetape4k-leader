package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseAnnotations
import io.bluetape4k.support.requireNotBlank
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import java.time.Clock
import java.time.Instant

/**
 * `KubernetesLeaseStateMapper`는 Kubernetes Lease spec과 annotations를 core [LeaderState]로 변환합니다.
 *
 */
object KubernetesLeaseStateMapper {

    /**
     * Kubernetes Lease의 holder와 만료 시각을 [LeaderState]로 변환합니다.
     *
     * `spec`, holder, renew/acquire 시각 또는 duration이 없거나 Lease가 만료되었으면 빈 상태를 반환합니다.
     * 유효한 상태에서는 `leader.bluetape4k.io/audit-leader-id` annotation을 audit ID로 사용하고,
     * 없으면 holder identity를 사용하며, node annotation은 선택적으로 보존합니다.
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

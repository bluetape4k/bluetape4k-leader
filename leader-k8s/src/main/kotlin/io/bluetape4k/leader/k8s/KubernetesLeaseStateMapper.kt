package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseAnnotations
import io.bluetape4k.support.requireNotBlank
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import java.time.Clock
import java.time.Instant

/**
 * `KubernetesLeaseStateMapper`는 Kubernetes Lease backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
object KubernetesLeaseStateMapper {

    /**
     * `map` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
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

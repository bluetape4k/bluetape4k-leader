package io.bluetape4k.leader.k8s.internal

internal object KubernetesLeaseAnnotations {
    const val AuditLeaderId = "leader.bluetape4k.io/audit-leader-id"
    const val ManagedBy = "leader.bluetape4k.io/managed-by"
    const val NodeId = "leader.bluetape4k.io/node-id"
    const val ManagedByValue = "bluetape4k-leader-k8s"
}

package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.support.requireNotBlank

internal object KubernetesLeaseNames {
    private val Dns1123Label = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")

    fun validateLeaseName(lockName: String) {
        lockName.requireNotBlank("lockName")
        require(lockName.length <= 63) {
            "lockName must be at most 63 characters for Kubernetes Lease name. lockName=$lockName"
        }
        require(Dns1123Label.matches(lockName)) {
            "lockName must be a DNS-1123 label for Kubernetes Lease name. lockName=$lockName"
        }
    }
}

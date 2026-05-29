package io.bluetape4k.leader.k8s.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal class KubernetesLeaseGroupAcquisitionDeadline private constructor(
    private val expiresAtNanos: Long,
) {
    fun remaining(): Duration =
        (expiresAtNanos - System.nanoTime()).coerceAtLeast(0L).nanoseconds

    companion object {
        fun fromNow(waitTime: Duration): KubernetesLeaseGroupAcquisitionDeadline =
            KubernetesLeaseGroupAcquisitionDeadline(System.nanoTime() + waitTime.inWholeNanoseconds.coerceAtLeast(0L))
    }
}

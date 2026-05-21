package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Options for Kubernetes Lease-backed leader election.
 *
 * ## Behavior / Contract
 * - [leaderOptions] controls wait time, lease duration, node identity, minimum lease time, and auto-extension.
 * - [namespace] is the namespace that stores `coordination.k8s.io/v1` Lease objects.
 * - [retryDelay] bounds full-jitter retry sleeps after contention or Kubernetes resource-version conflicts.
 *
 * ```kotlin
 * val options = KubernetesLeaseOptions(
 *     namespace = "operators",
 *     leaderOptions = LeaderElectionOptions(leaseTime = 30.seconds),
 * )
 * ```
 */
data class KubernetesLeaseOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val namespace: String = "default",
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {
    init {
        namespace.requireNotBlank("namespace")
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * Default options instance using namespace `default` and `LeaderElectionOptions.Default`.
         */
        @JvmField
        val Default = KubernetesLeaseOptions()

        private const val serialVersionUID = 1L
    }
}

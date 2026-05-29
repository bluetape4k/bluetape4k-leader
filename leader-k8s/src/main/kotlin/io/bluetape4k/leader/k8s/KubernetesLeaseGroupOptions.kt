package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Options for Kubernetes Lease-backed group leader election.
 *
 * ## Behavior / Contract
 * - [leaderGroupOptions] controls max leaders, wait time, lease duration, node identity, and minimum lease time.
 * - [namespace] stores one `coordination.k8s.io/v1` Lease per group slot.
 * - [retryDelay] bounds full-jitter retry sleeps after contention or Kubernetes resource-version conflicts.
 *
 * ```kotlin
 * val options = KubernetesLeaseGroupOptions(
 *     namespace = "operators",
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 4),
 * )
 * ```
 */
data class KubernetesLeaseGroupOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val namespace: String = "default",
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    init {
        namespace.requireNotBlank("namespace")
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    companion object {
        /**
         * Default group options instance using namespace `default` and [LeaderGroupElectionOptions.Default].
         */
        @JvmField
        val Default = KubernetesLeaseGroupOptions()

        private const val serialVersionUID = 1L
    }
}

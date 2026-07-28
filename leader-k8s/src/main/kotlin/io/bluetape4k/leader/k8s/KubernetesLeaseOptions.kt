package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseNames
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `KubernetesLeaseOptions`는 Kubernetes Lease backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions Kubernetes Lease backend 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property namespace Kubernetes Lease backend 계약에서 `namespace` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay Kubernetes Lease backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class KubernetesLeaseOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val namespace: String = "default",
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {
    init {
        KubernetesLeaseNames.validateNamespace(namespace)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * `Default` 값은 Kubernetes Lease backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default = KubernetesLeaseOptions()

        private const val serialVersionUID = 1L
    }
}

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
 * @property leaderOptions 단일 리더의 대기 시간, Lease 유지 시간, 자동 연장, 최소 유지 시간, node ID를
 *   포함하는 공통 선출 옵션입니다.
 * @property namespace Lease 리소스를 생성하고 조회할 Kubernetes Namespace입니다. DNS-1123 label 형식이며
 *   최대 63자이고 기본값은 `"default"`입니다.
 * @property retryDelay Lease 획득 충돌 시 다음 조회 전까지의 양의 지연 시간입니다. 기본값은 `50.milliseconds`입니다.
 */
data class KubernetesLeaseOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val namespace: String = "default",
    val retryDelay: Duration = 50.milliseconds,
): Serializable {
    init {
        KubernetesLeaseNames.validateNamespace(namespace)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /** 기본 namespace와 50ms 재시도 지연을 사용하는 단일 리더용 기본 옵션입니다. */
        @JvmField
        val Default = KubernetesLeaseOptions()

        private const val serialVersionUID = 1L
    }
}

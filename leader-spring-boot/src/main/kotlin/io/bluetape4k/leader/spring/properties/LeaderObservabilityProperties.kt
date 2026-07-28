package io.bluetape4k.leader.spring.properties

import java.io.Serializable
import java.time.Duration

/**
 * `LeaderObservabilityProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNames Spring Boot integration 계약에서 `lockNames` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property tracing Spring Boot integration 계약에서 `tracing` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property health Spring Boot integration 계약에서 `health` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
    val tracing: LeaderTracingProperties = LeaderTracingProperties(),
    val health: LeaderObservabilityHealthProperties = LeaderObservabilityHealthProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * `LeaderObservabilityHealthProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseWarningThreshold Spring Boot integration 계약에서 `leaseWarningThreshold` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderObservabilityHealthProperties(
    val enabled: Boolean = false,
    val leaseWarningThreshold: Duration = Duration.ofSeconds(10),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(!leaseWarningThreshold.isNegative) {
            "observability.health.leaseWarningThreshold must not be negative: $leaseWarningThreshold"
        }
    }
}

/**
 * `LeaderTracingProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeLockName Spring Boot integration 계약에서 `includeLockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeLeaderId Spring Boot integration 계약에서 `includeLeaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeExceptionDetails Spring Boot integration 계약에서 `includeExceptionDetails` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderTracingProperties(
    val enabled: Boolean = true,
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

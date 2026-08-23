package io.bluetape4k.leader.spring.properties

import java.io.Serializable
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * `LeaderObservabilityProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNames Spring Boot integration 계약에서 `lockNames` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property stateProviderBean Actuator와 readiness가 조회할 blocking 또는 suspend `LeaderElectionState` bean 이름입니다.
 * @property tracing Spring Boot integration 계약에서 `tracing` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property health Spring Boot integration 계약에서 `health` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property backendHealth backend connectivity health probe 설정입니다.
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
    val tracing: LeaderTracingProperties = LeaderTracingProperties(),
    val health: LeaderObservabilityHealthProperties = LeaderObservabilityHealthProperties(),
    val stateProviderBean: String = "",
    val backendHealth: LeaderBackendHealthProperties = LeaderBackendHealthProperties(),
) : Serializable {
    /** 0.4.0 공개 API의 두 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(enabled: Boolean, lockNames: Set<String>) : this(
        enabled = enabled,
        lockNames = lockNames,
        tracing = LeaderTracingProperties(),
        health = LeaderObservabilityHealthProperties(),
        stateProviderBean = "",
        backendHealth = LeaderBackendHealthProperties(),
    )

    /** backend health 추가 전에 공개된 다섯 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(
        enabled: Boolean,
        lockNames: Set<String>,
        tracing: LeaderTracingProperties,
        health: LeaderObservabilityHealthProperties,
        stateProviderBean: String,
    ) : this(
        enabled = enabled,
        lockNames = lockNames,
        tracing = tracing,
        health = health,
        stateProviderBean = stateProviderBean,
        backendHealth = LeaderBackendHealthProperties(),
    )

    /** Kotlin이 공개한 두 인자 기본 생성자 descriptor의 바이너리 호환성을 유지합니다. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        lockNames: Set<String>,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        enabled = if (mask and 0x001 != 0) true else enabled,
        lockNames = if (mask and 0x002 != 0) emptySet() else lockNames,
        tracing = LeaderTracingProperties(),
        health = LeaderObservabilityHealthProperties(),
        stateProviderBean = "",
        backendHealth = LeaderBackendHealthProperties(),
    )

    /** Kotlin이 공개한 다섯 인자 기본 생성자 descriptor의 바이너리 호환성을 유지합니다. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        lockNames: Set<String>,
        tracing: LeaderTracingProperties,
        health: LeaderObservabilityHealthProperties,
        stateProviderBean: String,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        enabled = if (mask and 0x001 != 0) true else enabled,
        lockNames = if (mask and 0x002 != 0) emptySet() else lockNames,
        tracing = if (mask and 0x004 != 0) LeaderTracingProperties() else tracing,
        health = if (mask and 0x008 != 0) LeaderObservabilityHealthProperties() else health,
        stateProviderBean = if (mask and 0x010 != 0) "" else stateProviderBean,
        backendHealth = LeaderBackendHealthProperties(),
    )

    /** 0.4.0 API의 두 인자 data class `copy` 진입점 바이너리 호환성을 유지합니다. */
    fun copy(enabled: Boolean, lockNames: Set<String>): LeaderObservabilityProperties = copy(
        enabled = enabled,
        lockNames = lockNames,
        tracing = tracing,
        health = health,
        stateProviderBean = stateProviderBean,
        backendHealth = backendHealth,
    )

    /** backend health 추가 전에 공개된 다섯 인자 data class `copy` 진입점 바이너리 호환성을 유지합니다. */
    fun copy(
        enabled: Boolean,
        lockNames: Set<String>,
        tracing: LeaderTracingProperties,
        health: LeaderObservabilityHealthProperties,
        stateProviderBean: String,
    ): LeaderObservabilityProperties = copy(
        enabled = enabled,
        lockNames = lockNames,
        tracing = tracing,
        health = health,
        stateProviderBean = stateProviderBean,
        backendHealth = backendHealth,
    )

    companion object {
        /** Kotlin이 공개한 두 인자 `copy$default` descriptor의 바이너리 호환성을 유지합니다. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderObservabilityProperties,
            enabled: Boolean,
            lockNames: Set<String>?,
            mask: Int,
            marker: Any?,
        ): LeaderObservabilityProperties = self.copy(
            enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            lockNames = if (mask and 0x002 != 0) self.lockNames else requireNotNull(lockNames),
        )

        /** Kotlin이 공개한 다섯 인자 `copy$default` descriptor의 바이너리 호환성을 유지합니다. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderObservabilityProperties,
            enabled: Boolean,
            lockNames: Set<String>?,
            tracing: LeaderTracingProperties?,
            health: LeaderObservabilityHealthProperties?,
            stateProviderBean: String?,
            mask: Int,
            marker: Any?,
        ): LeaderObservabilityProperties = self.copy(
            enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            lockNames = if (mask and 0x002 != 0) self.lockNames else requireNotNull(lockNames),
            tracing = if (mask and 0x004 != 0) self.tracing else requireNotNull(tracing),
            health = if (mask and 0x008 != 0) self.health else requireNotNull(health),
            stateProviderBean = if (mask and 0x010 != 0) self.stateProviderBean else requireNotNull(stateProviderBean),
        )

        private const val serialVersionUID = 1L
    }
}

/**
 * Backend connectivity health probe 설정입니다.
 *
 * @property enabled health indicator 활성화 여부입니다.
 * @property timeout provider connectivity probe 제한 시간입니다.
 */
data class LeaderBackendHealthProperties(
    val enabled: Boolean = false,
    val timeout: Duration = DefaultTimeout,
) : Serializable {

    init {
        val kotlinTimeout = timeout.toKotlinDuration()
        require(kotlinTimeout.isFinite() && kotlinTimeout.isPositive()) {
            "observability.backend-health.timeout must be positive and finite: $timeout"
        }
    }

    companion object {
        /** Connectivity probe의 기본 제한 시간입니다. */
        val DefaultTimeout: Duration = Duration.ofMillis(500)

        private const val serialVersionUID = 1L
    }
}

/**
 * `LeaderObservabilityHealthProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseWarningThreshold Spring Boot integration 계약에서 `leaseWarningThreshold` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property acquisitionFailureWindow 최근 backend 획득 실패를 health에 반영하는 관찰 시간 범위입니다.
 */
data class LeaderObservabilityHealthProperties(
    val enabled: Boolean = false,
    val leaseWarningThreshold: Duration = Duration.ofSeconds(10),
    val acquisitionFailureWindow: Duration = Duration.ofMinutes(DefaultAcquisitionFailureWindowMinutes),
) : Serializable {
    /** acquisition failure window 추가 전에 공개된 두 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(
        enabled: Boolean,
        leaseWarningThreshold: Duration,
    ) : this(
        enabled = enabled,
        leaseWarningThreshold = leaseWarningThreshold,
        acquisitionFailureWindow = Duration.ofMinutes(DefaultAcquisitionFailureWindowMinutes),
    )

    /** acquisition failure window 추가 전에 공개된 두 인자 data class `copy` 진입점 호환성을 유지합니다. */
    fun copy(
        enabled: Boolean,
        leaseWarningThreshold: Duration,
    ): LeaderObservabilityHealthProperties = copy(
        enabled = enabled,
        leaseWarningThreshold = leaseWarningThreshold,
        acquisitionFailureWindow = acquisitionFailureWindow,
    )

    companion object {
        private const val DefaultAcquisitionFailureWindowMinutes = 5L

        /** Kotlin이 공개한 두 인자 `copy$default` descriptor의 바이너리 호환성을 유지합니다. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderObservabilityHealthProperties,
            enabled: Boolean,
            leaseWarningThreshold: Duration?,
            mask: Int,
            marker: Any?,
        ): LeaderObservabilityHealthProperties = self.copy(
            enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            leaseWarningThreshold = if (mask and 0x002 != 0) {
                self.leaseWarningThreshold
            } else {
                requireNotNull(leaseWarningThreshold)
            },
            acquisitionFailureWindow = self.acquisitionFailureWindow,
        )

        private const val serialVersionUID = 1L
    }

    init {
        require(!leaseWarningThreshold.isNegative) {
            "observability.health.leaseWarningThreshold must not be negative: $leaseWarningThreshold"
        }
        val kotlinWindow = acquisitionFailureWindow.toKotlinDuration()
        require(kotlinWindow.isFinite() && kotlinWindow.isPositive()) {
            "observability.health.acquisitionFailureWindow must be positive and finite: $acquisitionFailureWindow"
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

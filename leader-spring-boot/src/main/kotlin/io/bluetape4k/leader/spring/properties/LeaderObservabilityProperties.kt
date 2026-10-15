package io.bluetape4k.leader.spring.properties

import io.bluetape4k.support.requireNotNull
import java.io.Serializable
import java.time.Duration
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.time.toKotlinDuration

/**
 * leader 상태, tracing, health endpoint를 노출하는 Spring Boot observability 설정입니다.
 *
 * @property enabled observability 자동 구성을 활성화할지 여부이며 기본값은 `true`입니다.
 * @property lockNames 상태 endpoint와 readiness가 관찰할 lock 이름 집합이며 기본값은 빈 집합입니다. 빈 집합이면 lock을 자동으로 추측하지 않습니다.
 * @property tracing leader election observation 설정이며 기본값은 `LeaderTracingProperties()`입니다.
 * @property health lease 경고와 획득 실패를 health에 반영하는 설정이며 기본값은 `LeaderObservabilityHealthProperties()`입니다.
 * @property stateProviderBean Actuator와 readiness가 조회할 blocking 또는 suspend `LeaderElectionState` bean 이름입니다. 빈 문자열이면 자동 선택 경로를 사용합니다.
 * @property backendHealth backend connectivity health probe 설정이며 기본값은 비활성화된 `LeaderBackendHealthProperties()`입니다.
 */
data class LeaderObservabilityProperties(
    val enabled: Boolean = true,
    val lockNames: Set<String> = emptySet(),
    val tracing: LeaderTracingProperties = LeaderTracingProperties(),
    val health: LeaderObservabilityHealthProperties = LeaderObservabilityHealthProperties(),
    val stateProviderBean: String = "",
    val backendHealth: LeaderBackendHealthProperties = LeaderBackendHealthProperties(),
): Serializable {
    /** 0.4.0 공개 API의 두 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(enabled: Boolean, lockNames: Set<String>): this(
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
    ): this(
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
    ): this(
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
    ): this(
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

    /**
     * `backendHealth` 추가 전 직렬화 스트림을 읽을 때 Java serialization이 Kotlin 기본값을
     * 호출하지 않는 경계를 복구합니다.
     */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
    private fun readResolve(): Any =
        if (backendHealth == null) {
            LeaderObservabilityProperties(
                enabled = enabled,
                lockNames = lockNames,
                tracing = tracing,
                health = health,
                stateProviderBean = stateProviderBean,
                backendHealth = backendHealth ?: LeaderBackendHealthProperties(),
            )
        } else {
            this
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
): Serializable {

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
 * lease 만료 임박과 최근 backend 획득 실패를 health에 반영하는 설정입니다.
 *
 * @property enabled health indicator를 활성화할지 여부이며 기본값은 `false`입니다.
 * @property leaseWarningThreshold 남은 lease 시간이 이 값 이하일 때 경고 상태로 판단하는 비음수 기간이며 기본값은 10초입니다.
 * @property acquisitionFailureWindow 최근 backend 획득 실패를 health에 반영하는 양수 유한 관찰 기간이며 기본값은 5분입니다.
 */
data class LeaderObservabilityHealthProperties(
    val enabled: Boolean = false,
    val leaseWarningThreshold: Duration = Duration.ofSeconds(10),
    val acquisitionFailureWindow: Duration = Duration.ofMinutes(DefaultAcquisitionFailureWindowMinutes),
): Serializable {
    /** `0.5.0`에서 Kotlin 기본 인자 호출자가 사용한 synthetic constructor를 보존합니다. */
    @Deprecated("0.5.0 JVM ABI 호환성 생성자", level = DeprecationLevel.HIDDEN)
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        leaseWarningThreshold: Duration?,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ): this(
        enabled = if (mask and 0x001 != 0) false else enabled,
        leaseWarningThreshold = if (mask and 0x002 != 0) {
            Duration.ofSeconds(10)
        } else {
            leaseWarningThreshold.requireNotNull("leaseWarningThreshold")
        },
        acquisitionFailureWindow = Duration.ofMinutes(DefaultAcquisitionFailureWindowMinutes),
    )

    /** acquisition failure window 추가 전에 공개된 두 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(
        enabled: Boolean,
        leaseWarningThreshold: Duration,
    ): this(
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

    /**
     * `acquisitionFailureWindow` 추가 전 직렬화 스트림을 읽을 때 Java serialization이 Kotlin
     * 기본값을 호출하지 않는 경계를 복구합니다.
     */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    private fun readResolve(): Any =
        if (acquisitionFailureWindow == null) {
            LeaderObservabilityHealthProperties(
                enabled = enabled,
                leaseWarningThreshold = leaseWarningThreshold,
                acquisitionFailureWindow = Duration.ofMinutes(DefaultAcquisitionFailureWindowMinutes),
            )
        } else {
            this
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
 * leader election observation에 포함할 metric과 오류 세부 정보의 Spring Boot 설정입니다.
 *
 * @property enabled tracing recorder를 활성화할지 여부이며 기본값은 `true`입니다.
 * @property includeLockName observation tag에 lock 이름을 포함할지 여부이며 기본값은 `false`입니다. 포함하더라도 micrometer tag sanitization 규칙을 따릅니다.
 * @property includeLeaderId observation tag에 leader ID를 포함할지 여부이며 기본값은 `false`입니다.
 * @property includeExceptionDetails observation 오류에 예외 세부 정보를 포함할지 여부이며 기본값은 `false`입니다.
 */
data class LeaderTracingProperties(
    val enabled: Boolean = true,
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

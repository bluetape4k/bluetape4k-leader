package io.bluetape4k.leader.spring.properties

import io.bluetape4k.support.requireNotNull
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.io.Serializable

/**
 * route guard가 요청 허용 여부를 판단할 authority 출처입니다.
 */
enum class LeaderRouteAuthorityMode {
    /**
     * 등록된 `LeaderElectionState` provider의 현재 상태를 authority로 사용합니다.
     */
    STATE,

    /**
     * `electorBean`으로 지정한 사용자 제공 `LeaderRouteAuthority` bean을 authority로 사용합니다.
     */
    CUSTOM,

    /** 요청 전체를 하나의 bounded lease handle로 보호하는 모드입니다. */
    LEASE,
}

/**
 * route guard가 요청을 거부할 때 응답할 HTTP status code를 정의합니다.
 *
 * @property value 해당 enum 항목에 대응하는 HTTP status code 정수입니다.
 */
enum class LeaderRouteRejectionStatus(
    val value: Int,
) {
    NOT_FOUND(404),
    CONFLICT(409),
    LOCKED(423),
    SERVICE_UNAVAILABLE(503),
}

/**
 * HTTP route가 leader 권한을 가질 때만 실행되도록 제한하는 Spring Boot 설정입니다.
 *
 * @property enabled route guard 자동 구성을 활성화할지 여부이며 기본값은 `false`입니다.
 * @property authorityMode authority를 state provider에서 읽을지 사용자 bean에서 읽을지 정하며 기본값은 `STATE`입니다.
 * @property electorBean `CUSTOM` 모드에서 사용할 `LeaderRouteAuthority` bean 이름이며 기본값인 빈 문자열은 사용자 bean을 지정하지 않았음을 뜻합니다.
 * @property rejectionStatus 요청을 거부할 때 사용할 HTTP status이며 기본값은 `SERVICE_UNAVAILABLE`(503)입니다.
 * @property lease 요청별 lease admission과 cleanup 상한입니다.
 */
data class LeaderRouteGuardProperties(
    val enabled: Boolean = false,
    val authorityMode: LeaderRouteAuthorityMode = LeaderRouteAuthorityMode.STATE,
    val electorBean: String = "",
    val rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
    val redirect: LeaderRouteRedirectProperties = LeaderRouteRedirectProperties(),
    @field:NestedConfigurationProperty
    val lease: LeaderRouteLeaseProperties = LeaderRouteLeaseProperties(),
): Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Preserves Kotlin's four-argument `copy$default` descriptor. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderRouteGuardProperties,
            enabled: Boolean,
            authorityMode: LeaderRouteAuthorityMode?,
            electorBean: String?,
            rejectionStatus: LeaderRouteRejectionStatus?,
            mask: Int,
            marker: Any?,
        ): LeaderRouteGuardProperties = self.copy(
            enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            authorityMode = if (mask and 0x002 != 0) self.authorityMode else authorityMode.requireNotNull(
                "authorityMode",
            ),
            electorBean = if (mask and 0x004 != 0) self.electorBean else electorBean.requireNotNull(
                "electorBean",
            ),
            rejectionStatus = if (mask and 0x008 != 0) self.rejectionStatus else rejectionStatus.requireNotNull(
                "rejectionStatus",
            ),
            redirect = self.redirect,
            lease = self.lease,
        )

        /** Preserves Kotlin's five-argument `copy$default` descriptor. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderRouteGuardProperties,
            enabled: Boolean,
            authorityMode: LeaderRouteAuthorityMode?,
            electorBean: String?,
            rejectionStatus: LeaderRouteRejectionStatus?,
            redirect: LeaderRouteRedirectProperties?,
            mask: Int,
            marker: Any?,
        ): LeaderRouteGuardProperties = self.copy(
            enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            authorityMode = if (mask and 0x002 != 0) self.authorityMode else authorityMode.requireNotNull(
                "authorityMode",
            ),
            electorBean = if (mask and 0x004 != 0) self.electorBean else electorBean.requireNotNull(
                "electorBean",
            ),
            rejectionStatus = if (mask and 0x008 != 0) self.rejectionStatus else rejectionStatus.requireNotNull(
                "rejectionStatus",
            ),
            redirect = if (mask and 0x010 != 0) self.redirect else redirect.requireNotNull(
                "redirect",
            ),
            lease = self.lease,
        )

    }

    /** Preserves the four-argument constructor published before redirect policy support. */
    constructor(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
    ): this(enabled, authorityMode, electorBean, rejectionStatus, LeaderRouteRedirectProperties())

    /** Preserves Kotlin's four-argument default-constructor descriptor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
        redirect: LeaderRouteRedirectProperties,
    ): this(enabled, authorityMode, electorBean, rejectionStatus, redirect, LeaderRouteLeaseProperties())

    /** Preserves the five-argument constructor published with redirect policy support. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
        redirect: LeaderRouteRedirectProperties,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ): this(
        enabled = if (mask and 0x001 != 0) false else enabled,
        authorityMode = if (mask and 0x002 != 0) LeaderRouteAuthorityMode.STATE else authorityMode,
        electorBean = if (mask and 0x004 != 0) "" else electorBean,
        rejectionStatus = if (mask and 0x008 != 0) LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE else rejectionStatus,
        redirect = if (mask and 0x010 != 0) LeaderRouteRedirectProperties() else redirect,
        lease = LeaderRouteLeaseProperties(),
    )

    /** Preserves Kotlin's five-argument data-class copy entry point. */
    fun copy(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
        redirect: LeaderRouteRedirectProperties,
    ): LeaderRouteGuardProperties = copy(
        enabled = enabled,
        authorityMode = authorityMode,
        electorBean = electorBean,
        rejectionStatus = rejectionStatus,
        redirect = redirect,
        lease = lease,
    )

    /** Preserves the four-argument constructor published before redirect policy support. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ): this(
        enabled = if (mask and 0x001 != 0) false else enabled,
        authorityMode = if (mask and 0x002 != 0) LeaderRouteAuthorityMode.STATE else authorityMode,
        electorBean = if (mask and 0x004 != 0) "" else electorBean,
        rejectionStatus = if (mask and 0x008 != 0) LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE else rejectionStatus,
        redirect = LeaderRouteRedirectProperties(),
        lease = LeaderRouteLeaseProperties(),
    )

    /** Preserves the four-argument data-class copy entry point. */
    fun copy(
        enabled: Boolean,
        authorityMode: LeaderRouteAuthorityMode,
        electorBean: String,
        rejectionStatus: LeaderRouteRejectionStatus,
    ): LeaderRouteGuardProperties = copy(
        enabled = enabled,
        authorityMode = authorityMode,
        electorBean = electorBean,
        rejectionStatus = rejectionStatus,
        redirect = redirect,
        lease = lease,
    )

    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
    private fun readResolve(): Any =
        if (redirect == null || lease == null) {
            LeaderRouteGuardProperties(
                enabled = enabled,
                authorityMode = authorityMode,
                electorBean = electorBean,
                rejectionStatus = rejectionStatus,
                redirect = redirect ?: LeaderRouteRedirectProperties(),
                lease = lease ?: LeaderRouteLeaseProperties(),
            )
        } else {
            this
        }
}

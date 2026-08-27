package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.time.Duration
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.time.toKotlinDuration

/**
 * `LeaderGroupProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property maxLeaders Spring Boot integration 계약에서 `maxLeaders` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime Spring Boot integration 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime Spring Boot integration 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property useDbTime Exposed JDBC/R2DBC group ownership에 DB server time을 사용할지 여부입니다.
 */
data class LeaderGroupProperties(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val useDbTime: Boolean = false,
) {
    /** `useDbTime` 추가 전에 공개된 세 인자 생성자 descriptor를 보존합니다. */
    constructor(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
    ) : this(maxLeaders, waitTime, leaseTime, false)

    /** Kotlin이 `useDbTime` 추가 전에 공개한 세 인자 기본 생성자 descriptor를 보존합니다. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
        maxLeaders = if (mask and 0x001 != 0) DefaultMaxLeaders else maxLeaders,
        waitTime = if (mask and 0x002 != 0) DefaultWaitTime else waitTime,
        leaseTime = if (mask and 0x004 != 0) DefaultLeaseTime else leaseTime,
        useDbTime = false,
    )

    /** `useDbTime` 추가 전에 공개된 세 인자 data-class `copy` 진입점을 보존합니다. */
    fun copy(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
    ): LeaderGroupProperties = copy(
        maxLeaders = maxLeaders,
        waitTime = waitTime,
        leaseTime = leaseTime,
        useDbTime = useDbTime,
    )
    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = Duration.ofSeconds(5)
        val DefaultLeaseTime: Duration = Duration.ofSeconds(60)

        /** `useDbTime` 추가 전에 공개된 세 인자 `copy$default` descriptor를 보존합니다. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderGroupProperties,
            maxLeaders: Int,
            waitTime: Duration?,
            leaseTime: Duration?,
            mask: Int,
            marker: Any?,
        ): LeaderGroupProperties = self.copy(
            maxLeaders = if (mask and 0x001 != 0) self.maxLeaders else maxLeaders,
            waitTime = if (mask and 0x002 != 0) self.waitTime else requireNotNull(waitTime),
            leaseTime = if (mask and 0x004 != 0) self.leaseTime else requireNotNull(leaseTime),
            useDbTime = self.useDbTime,
        )
    }

    fun toOptions(): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = maxLeaders,
            waitTime = waitTime.toKotlinDuration(),
            leaseTime = leaseTime.toKotlinDuration(),
            useDbTime = useDbTime,
        )
}

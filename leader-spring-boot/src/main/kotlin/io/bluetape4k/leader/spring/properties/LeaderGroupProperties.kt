package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.time.Duration
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.time.toKotlinDuration

/**
 * Spring Boot group election의 동시 leadership 슬롯과 공통 lease 정책입니다.
 *
 * @property maxLeaders 동시에 leadership을 보유할 수 있는 최대 슬롯 수입니다. 기본값은 `2`이며 core options가 유효 범위를 검증합니다.
 * @property waitTime 슬롯 획득을 기다릴 최대 시간입니다. 기본값은 5초입니다.
 * @property leaseTime 슬롯 lease TTL입니다. 기본값은 60초입니다.
 * @property useDbTime Exposed JDBC/R2DBC group ownership에서 DB server clock으로 만료 시각을 계산할지 정합니다. 기본값은 `false`입니다.
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
    ): this(maxLeaders, waitTime, leaseTime, false)

    /** Kotlin이 `useDbTime` 추가 전에 공개한 세 인자 기본 생성자 descriptor를 보존합니다. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ): this(
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

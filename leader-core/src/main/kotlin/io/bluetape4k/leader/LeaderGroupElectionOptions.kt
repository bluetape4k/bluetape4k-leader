package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderGroupElectionOptions`는 동시에 leadership을 보유할 수 있는 슬롯과 각 lease의 정책을 설정합니다.
 *
 * @property maxLeaders 동시에 획득할 수 있는 최대 슬롯 수입니다. `1` 이상이어야 하며 기본값은 `2`입니다.
 * @property waitTime 슬롯 획득을 기다릴 최대 시간입니다. `0`이면 추가로 기다리지 않으며, 기본값은 5초입니다.
 * @property leaseTime 획득한 슬롯의 lease TTL입니다. 양수여야 하며 기본값은 60초입니다.
 * @property nodeId 이 프로세스의 leader 후보 식별자입니다. 공백은 허용되지 않으며 기본값은 `LeaderNodeId.Default`입니다.
 * @property minLeaseTime 작업이 일찍 끝나도 획득 시점부터 유지할 최소 시간입니다. `0` 이상 `leaseTime` 이하이며 기본값은 `0`입니다.
 * @property useDbTime Exposed JDBC/R2DBC backend가 만료 시각 계산에 DB server clock을 사용할지 정합니다. 다른 backend는 이 옵션을 사용하지 않으며 기본값은 `false`입니다.
 */
data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
    val useDbTime: Boolean = false,
): Serializable {

    /** 기존 0.5.x JVM constructor descriptor를 보존하는 호환성 overload입니다. */
    private constructor(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
        nodeId: String,
        minLeaseTime: Duration,
    ): this(maxLeaders, waitTime, leaseTime, nodeId, minLeaseTime, false)

    /**
     * 기존 Kotlin 기본 인자 호출자가 사용하던 0.5.x synthetic constructor descriptor를
     * 보존합니다. `DefaultConstructorMarker`는 컴파일러가 삽입하던 인자이며 호출자가
     * 직접 의미 있는 값을 전달하지 않습니다.
     */
    @Suppress("LongParameterList", "UnusedParameter", "UNUSED_PARAMETER")
    constructor(
        maxLeaders: Int,
        waitTimeNanos: Long,
        leaseTimeNanos: Long,
        nodeId: String,
        minLeaseTimeNanos: Long,
        marker: DefaultConstructorMarker?,
    ): this(
        maxLeaders,
        waitTimeNanos.nanoseconds,
        leaseTimeNanos.nanoseconds,
        nodeId,
        minLeaseTimeNanos.nanoseconds,
        false,
    )

    /** 0.5.x의 기본 인자 mask constructor descriptor를 보존합니다. */
    @Suppress("LongParameterList", "UnusedParameter", "UNUSED_PARAMETER")
    constructor(
        maxLeaders: Int,
        waitTimeNanos: Long,
        leaseTimeNanos: Long,
        nodeId: String,
        minLeaseTimeNanos: Long,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ): this(
        maxLeaders = if (mask and 1 != 0) DefaultMaxLeaders else maxLeaders,
        waitTime = if (mask and 2 != 0) DefaultWaitTime else waitTimeNanos.nanoseconds,
        leaseTime = if (mask and 4 != 0) DefaultLeaseTime else leaseTimeNanos.nanoseconds,
        nodeId = if (mask and 8 != 0) LeaderNodeId.Default else nodeId,
        minLeaseTime = if (mask and 16 != 0) Duration.ZERO else minLeaseTimeNanos.nanoseconds,
        useDbTime = false,
    )

    /** 기존 Kotlin Duration-mangled copy descriptor를 보존하는 호환성 overload입니다. */
    @Suppress("FunctionNaming", "LongParameterList")
    @JvmName("copy-5t7Pxr8")
    fun `copy-5t7Pxr8`(
        maxLeaders: Int,
        waitTime: Duration,
        leaseTime: Duration,
        nodeId: String,
        minLeaseTime: Duration,
    ): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(maxLeaders, waitTime, leaseTime, nodeId, minLeaseTime, useDbTime)

    init {
        maxLeaders.requireGe(1, "maxLeaders")
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        nodeId.requireNotBlank("nodeId")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        require(minLeaseTime <= leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * 슬롯 2개, 대기 시간 5초, lease TTL 60초, 최소 유지 시간 0으로 구성한 기본 옵션입니다.
         */
        @JvmField
        val Default = LeaderGroupElectionOptions()

        /** 기존 Kotlin compiler `$default` descriptor를 보존하는 호환성 bridge입니다. */
        @Suppress("LongParameterList", "UnusedParameter")
        @JvmStatic
        @JvmName("copy-5t7Pxr8\$default")
        fun copyDefaultCompatibility(
            self: LeaderGroupElectionOptions,
            maxLeaders: Int,
            waitTime: Duration,
            leaseTime: Duration,
            nodeId: String,
            minLeaseTime: Duration,
            mask: Int,
            marker: Any?,
        ): LeaderGroupElectionOptions = LeaderGroupElectionOptions(
            maxLeaders = if (mask and 1 != 0) self.maxLeaders else maxLeaders,
            waitTime = if (mask and 2 != 0) self.waitTime else waitTime,
            leaseTime = if (mask and 4 != 0) self.leaseTime else leaseTime,
            nodeId = if (mask and 8 != 0) self.nodeId else nodeId,
            minLeaseTime = if (mask and 16 != 0) self.minLeaseTime else minLeaseTime,
            useDbTime = self.useDbTime,
        )

        private const val serialVersionUID = 1L
    }
}

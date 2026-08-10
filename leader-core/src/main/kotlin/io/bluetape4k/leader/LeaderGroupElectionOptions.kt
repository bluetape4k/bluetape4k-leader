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
 * `LeaderGroupElectionOptions`는 group leader election 옵션입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
 * @property waitTime leader lock 획득을 기다리는 최대 시간입니다.
 * @property leaseTime leadership을 보유할 수 있는 lease TTL입니다.
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 * @property minLeaseTime 작업이 빨리 끝나더라도 lease를 최소로 유지할 시간입니다.
 * @property useDbTime Exposed JDBC/R2DBC group ownership에 DB server time을 사용할지 여부입니다.
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
    ) : this(maxLeaders, waitTime, leaseTime, nodeId, minLeaseTime, false)

    /**
     * 기존 Kotlin 기본 인자 호출자가 사용하던 0.5.x synthetic constructor descriptor를
     * 보존합니다. `DefaultConstructorMarker`는 컴파일러가 삽입하던 인자이며 호출자가
     * 직접 의미 있는 값을 전달하지 않습니다.
     */
    @Suppress("LongParameterList", "UnusedParameter", "UNUSED_PARAMETER")
    public constructor(
        maxLeaders: Int,
        waitTimeNanos: Long,
        leaseTimeNanos: Long,
        nodeId: String,
        minLeaseTimeNanos: Long,
        marker: DefaultConstructorMarker?,
    ) : this(
        maxLeaders,
        waitTimeNanos.nanoseconds,
        leaseTimeNanos.nanoseconds,
        nodeId,
        minLeaseTimeNanos.nanoseconds,
        false,
    )

    /** 0.5.x의 기본 인자 mask constructor descriptor를 보존합니다. */
    @Suppress("LongParameterList", "UnusedParameter", "UNUSED_PARAMETER")
    public constructor(
        maxLeaders: Int,
        waitTimeNanos: Long,
        leaseTimeNanos: Long,
        nodeId: String,
        minLeaseTimeNanos: Long,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
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
         * `Default` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
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

package io.bluetape4k.leader.consul

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.consul.internal.ConsulLeaderPaths
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `ConsulLeaderElectionOptions`는 Consul backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions Consul backend 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix Consul backend 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sessionNamePrefix Consul backend 계약에서 `sessionNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockDelay Consul backend 계약에서 `lockDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ConsulLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
) : Serializable {

    init {
        ConsulLeaderPaths.validatePrefix(keyPrefix)
        sessionNamePrefix.requireNotBlank("sessionNamePrefix")
        leaderOptions.leaseTime.requireGe(MinLeaseTime, "leaderOptions.leaseTime")
        leaderOptions.leaseTime.requireLe(MaxLeaseTime, "leaderOptions.leaseTime")
        lockDelay.requireGe(Duration.ZERO, "lockDelay")
    }

    companion object {
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"

        val MinLeaseTime: Duration = 10.seconds

        val MaxLeaseTime: Duration = 86_400.seconds

        @JvmField
        val Default = ConsulLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

/**
 * `ConsulLeaderGroupElectionOptions`는 Consul backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions Consul backend 계약에서 `leaderGroupOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix Consul backend 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sessionNamePrefix Consul backend 계약에서 `sessionNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockDelay Consul backend 계약에서 `lockDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ConsulLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    val sessionNamePrefix: String = ConsulLeaderElectionOptions.DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
) : Serializable {

    /**
     * `maxLeaders` 값은 Consul backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        ConsulLeaderPaths.validatePrefix(keyPrefix)
        sessionNamePrefix.requireNotBlank("sessionNamePrefix")
        leaderGroupOptions.leaseTime.requireGe(
            ConsulLeaderElectionOptions.MinLeaseTime,
            "leaderGroupOptions.leaseTime",
        )
        leaderGroupOptions.leaseTime.requireLe(
            ConsulLeaderElectionOptions.MaxLeaseTime,
            "leaderGroupOptions.leaseTime",
        )
        lockDelay.requireGe(Duration.ZERO, "lockDelay")
    }

    companion object {
        @JvmField
        val Default = ConsulLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}

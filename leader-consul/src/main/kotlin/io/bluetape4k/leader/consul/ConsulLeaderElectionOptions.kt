package io.bluetape4k.leader.consul

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.consul.internal.ConsulLeaderPaths
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveDuration
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * `ConsulLeaderElectionOptions`는 Consul backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions lease·대기·최소 lease·자동 연장과 owner를 포함한 공통 단일 선거 설정입니다.
 * @property keyPrefix Consul KV lock 키의 루트 경로이며 Consul 경로 규칙으로 검증됩니다.
 * @property sessionNamePrefix Consul session 이름 앞부분이며 공백 문자만 포함한 값은 허용하지 않습니다.
 * @property lockDelay session 해제 뒤 Consul이 lock을 재획득 가능하게 하기 전 지연이며 0 이상입니다.
 */
data class ConsulLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
): Serializable {

    init {
        ConsulLeaderPaths.validatePrefix(keyPrefix)
        sessionNamePrefix.requireNotBlank("sessionNamePrefix")
        leaderOptions.leaseTime.requireGe(MinLeaseTime, "leaderOptions.leaseTime")
        leaderOptions.leaseTime.requireLe(MaxLeaseTime, "leaderOptions.leaseTime")
        lockDelay.requireZeroOrPositiveDuration("lockDelay")
    }

    companion object {
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"

        val MinLeaseTime: Duration = 10.seconds

        val MaxLeaseTime: Duration = 24.hours

        @JvmField
        val Default = ConsulLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}

/**
 * `ConsulLeaderGroupElectionOptions`는 Consul backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions maxLeaders·lease·대기·최소 lease를 포함한 공통 그룹 선거 설정입니다.
 * @property keyPrefix Consul KV group lock 키의 루트 경로이며 Consul 경로 규칙으로 검증됩니다.
 * @property sessionNamePrefix Consul group session 이름 앞부분이며 공백 문자만 포함한 값은 허용하지 않습니다.
 * @property lockDelay session 해제 뒤 Consul이 lock을 재획득 가능하게 하기 전 지연이며 0 이상입니다.
 */
data class ConsulLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    val sessionNamePrefix: String = ConsulLeaderElectionOptions.DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
): Serializable {

    /** lock name별로 동시에 허용되는 활성 슬롯의 상한입니다. 공통 그룹 옵션에서 가져옵니다. */
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
        lockDelay.requireZeroOrPositiveDuration("lockDelay")
    }

    companion object {
        @JvmField
        val Default = ConsulLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}

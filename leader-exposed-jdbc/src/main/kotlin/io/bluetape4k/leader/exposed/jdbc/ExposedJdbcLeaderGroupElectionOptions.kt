package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * `ExposedJdbcLeaderGroupElectionOptions`는 Exposed JDBC group election의 슬롯, lease, 재시도, owner 정책을 설정합니다.
 *
 * @property leaderGroupOptions 동시에 허용할 슬롯 수와 각 슬롯의 대기·lease 정책을 정하는 공통 group 옵션입니다. 기본값은
 * `LeaderGroupElectionOptions.Default`입니다.
 * @property retryStrategy 슬롯 획득을 반복할 때 사용할 재시도 지연 계산기입니다. 기본값은 50ms jitter입니다.
 * @property lockOwner 각 group lock 행에 기록할 선택적 owner 식별자입니다. `null`이면 owner를 저장하지 않으며, 지정하면 최대 255자입니다.
 */
data class ExposedJdbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val lockOwner: String? = null,
): Serializable {

    /** 공통 group 옵션이 허용하는 동시 leadership 슬롯 수입니다. `1` 이상으로 검증됩니다. */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /** 공통 기본 group 옵션과 50ms jitter를 사용하고 owner를 저장하지 않는 JDBC 옵션입니다. */
        @JvmField
        val Default = ExposedJdbcLeaderGroupElectionOptions()
    }
}

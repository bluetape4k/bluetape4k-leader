package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import java.io.Serializable

/**
 * `ExposedJdbcLeaderElectionOptions`는 Exposed JDBC 단일 leader election의 공통 lease, 재시도, owner 정책을 설정합니다.
 *
 * @property leaderOptions 대기 시간, lease TTL, 최소 유지 시간, DB 시각 사용 여부를 정하는 공통 단일 leader 옵션입니다. 기본값은
 * `LeaderElectionOptions.Default`입니다.
 * @property retryStrategy DB에서 lock을 얻지 못했을 때 사용할 재시도 지연 계산기입니다. 기본값은 50ms jitter입니다.
 * @property lockOwner lock 행에 기록할 선택적 owner 식별자입니다. `null`이면 owner를 저장하지 않으며, 지정하면 최대 255자입니다.
 */
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val lockOwner: String? = null,
): Serializable {

    init {
        lockOwner?.length?.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
    }

    companion object {
        /** 공통 기본 단일 leader 옵션과 50ms jitter를 사용하고 owner를 저장하지 않는 JDBC 옵션입니다. */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

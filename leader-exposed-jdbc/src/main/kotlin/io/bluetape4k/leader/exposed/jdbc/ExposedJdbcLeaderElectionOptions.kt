package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import java.io.Serializable

/**
 * `ExposedJdbcLeaderElectionOptions`는 Exposed database leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions Exposed database backend 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryStrategy Exposed database backend 계약에서 `retryStrategy` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockOwner Exposed database backend 계약에서 `lockOwner` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val lockOwner: String? = null,
) : Serializable {

    init {
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /**
         * `Default` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

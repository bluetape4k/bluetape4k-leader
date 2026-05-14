package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * Exposed JDBC 기반 복수 리더 그룹 선출 옵션.
 *
 * ```kotlin
 * val options = ExposedJdbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     retryStrategy = RetryStrategy.Exponential(),
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedJdbcLeaderGroupElector(db, options)
 * ```
 *
 * @property leaderGroupOptions 그룹 리더 선출 옵션 (maxLeaders, waitTime, leaseTime). `maxLeaders`는 양수여야 함
 * @property retryStrategy 락 획득 재시도 전략. 기본값 [RetryStrategy.Jitter]
 * @property lockOwner 락 보유자 식별자. 컬럼 폭 [ExposedLeaderConstants.LOCK_OWNER_LENGTH]자 이내. `null`이면 미기록
 */
data class ExposedJdbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val lockOwner: String? = null,
) : Serializable {

    /** 허용하는 최대 동시 리더 수 ([LeaderGroupElectionOptions.maxLeaders] 위임). */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /**
         * 기본 옵션 인스턴스.
         *
         * - leaderGroupOptions = [LeaderGroupElectionOptions.Default]
         * - retryStrategy = [RetryStrategy.Jitter]
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedJdbcLeaderGroupElectionOptions()
    }
}

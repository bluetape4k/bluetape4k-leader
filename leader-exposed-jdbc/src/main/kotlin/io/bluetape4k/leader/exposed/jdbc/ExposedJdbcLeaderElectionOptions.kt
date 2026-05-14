package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import java.io.Serializable

/**
 * Exposed JDBC 기반 단일 리더 선출 옵션.
 *
 * ```kotlin
 * val options = ExposedJdbcLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(
 *         waitTime = Duration.ofSeconds(3),
 *         leaseTime = Duration.ofSeconds(30),
 *     ),
 *     retryStrategy = RetryStrategy.Jitter(baseDelayMs = 100L),
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedJdbcLeaderElector(db, options)
 * ```
 *
 * @property leaderOptions 단일 리더 선출 옵션 (waitTime, leaseTime)
 * @property retryStrategy 락 획득 재시도 전략. 기본값 [RetryStrategy.Jitter]
 * @property lockOwner 락 보유자 식별자. 컬럼 폭 [ExposedLeaderConstants.LOCK_OWNER_LENGTH]자 이내. `null`이면 미기록
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
         * 기본 옵션 인스턴스.
         *
         * - leaderOptions = [LeaderElectionOptions.Default] (waitTime/leaseTime은 leader-core 기본값)
         * - retryStrategy = [RetryStrategy.Jitter] (baseDelayMs = 50ms)
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

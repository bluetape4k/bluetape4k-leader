package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
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
 *     recordHistory = true,
 *     lockOwner = "worker-1",
 * )
 * val election = ExposedJdbcLeaderElection(db, options)
 * ```
 *
 * @property leaderOptions 단일 리더 선출 옵션 (waitTime, leaseTime)
 * @property retryStrategy 락 획득 재시도 전략. 기본값 [RetryStrategy.Jitter]
 * @property recordHistory `true`이면 획득/완료/실패 이력을 [io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable]에 기록
 * @property lockOwner 락 보유자 식별자. 컬럼 폭 [ExposedLeaderConstants.LOCK_OWNER_LENGTH]자 이내. `null`이면 미기록
 */
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {

    init {
        lockOwner?.let {
            require(it.length <= ExposedLeaderConstants.LOCK_OWNER_LENGTH) {
                "lockOwner must be <= ${ExposedLeaderConstants.LOCK_OWNER_LENGTH} chars, but was ${it.length}"
            }
        }
    }

    companion object {
        /**
         * 기본 옵션 인스턴스.
         *
         * - leaderOptions = [LeaderElectionOptions.Default] (waitTime/leaseTime은 leader-core 기본값)
         * - retryStrategy = [RetryStrategy.Jitter] (baseDelayMs = 50ms)
         * - recordHistory = `false`
         * - lockOwner = `null`
         */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

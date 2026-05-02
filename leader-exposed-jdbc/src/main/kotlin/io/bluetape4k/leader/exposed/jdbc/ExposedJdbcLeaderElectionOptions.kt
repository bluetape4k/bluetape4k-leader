package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElectionOptions
import java.io.Serializable

/**
 * Exposed JDBC 기반 단일 리더 선출 옵션.
 *
 * ```kotlin
 * val options = ExposedJdbcLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds),
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
 * @property lockOwner 락 보유자 식별자. `null`이면 미기록
 */
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {

    init {
        lockOwner?.let {
            require(it.length <= 255) { "lockOwner must be <= 255 chars, but was ${it.length}" }
        }
    }

    companion object {
        /** 기본 옵션 인스턴스. */
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}

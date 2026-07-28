package io.bluetape4k.leader.history

import java.time.Instant

/**
 * `NoopLeaderHistorySink`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object NoopLeaderHistorySink : LeaderHistorySink {
    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null
    override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
    override fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) = Unit
}

/**
 * `NoopSuspendLeaderHistorySink`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object NoopSuspendLeaderHistorySink : SuspendLeaderHistorySink {
    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null
    override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
    override suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) = Unit
}

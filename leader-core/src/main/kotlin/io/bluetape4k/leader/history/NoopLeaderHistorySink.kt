package io.bluetape4k.leader.history

import java.time.Instant

/**
 * 모든 history 기록 호출을 저장 없이 처리하는 blocking sink입니다. acquire 요청은 항상 null을 반환합니다.
 */
object NoopLeaderHistorySink: LeaderHistorySink {
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
 * 모든 history 기록 호출을 저장 없이 처리하는 suspend sink입니다. acquire 요청은 항상 null을 반환합니다.
 */
object NoopSuspendLeaderHistorySink: SuspendLeaderHistorySink {
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

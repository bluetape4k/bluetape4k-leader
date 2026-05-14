package io.bluetape4k.leader.history

import java.time.Instant

/**
 * No-op [LeaderHistorySink] that discards all events.
 *
 * Use this when audit persistence is intentionally disabled.  It can be injected
 * explicitly or used as the default sink when no other bean is configured.
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
 * No-op [SuspendLeaderHistorySink] that discards all events.
 *
 * Use this when audit persistence is intentionally disabled for coroutine-based electors.
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

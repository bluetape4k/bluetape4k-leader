package io.bluetape4k.leader.history

import java.time.Instant

/**
 * Coroutine-aware SPI for persisting leader-lock lifecycle events.
 *
 * Implementations are expected to be **thread-safe**: a single sink instance is
 * shared across concurrent election coroutines.
 *
 * ## Behavior / Contract
 * - [recordAcquired] is called immediately after the lock is obtained, before the
 *   protected action starts.  It returns a [LeaderHistoryKey] that callers pass to
 *   [recordCompleted] or [recordFailed].
 * - If [recordAcquired] returns `null`, the caller **must** construct a fallback key
 *   from `(lockName, token)` and pass it to subsequent calls.
 * - [deleteOlderThan] has a no-op default; JDBC R2DBC backends override it.
 *   MongoDB uses `deleteMany` without a `LIMIT` clause and ignores [limit].
 *
 * ## Blocking I/O inside suspend functions
 * Implementations that call **synchronous blocking I/O** (e.g. JDBC) MUST wrap
 * those calls in `runInterruptible {}` so that coroutine cancellation correctly
 * restores the thread interrupt flag:
 *
 * ```kotlin
 * // MUST — JDBC / synchronous blocking call
 * override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
 *     runInterruptible {
 *         transaction(database) { LeaderLockHistoryTable.insert { … } get id }
 *     }
 *
 * // NOT needed — MongoDB Reactive Streams is natively async
 * override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
 *     collection.insertOne(document).awaitSingle()
 * }
 * ```
 *
 * ## null-key fallback contract
 * When [recordAcquired] returns null, callers create a fallback [LeaderHistoryKey]
 * using `(lockName, token)`.  Implementations must handle a `null` [LeaderHistoryKey.id]
 * gracefully (e.g. fall back to a natural-key update or no-op).
 *
 * ## CancellationException
 * This interface does **not** catch [kotlinx.coroutines.CancellationException]; the
 * recorder layer (`SuspendSafeLeaderHistoryRecorder`) rethrows it before any fallback
 * counter increments.
 */
interface SuspendLeaderHistorySink {

    /**
     * Persists an ACQUIRED event and returns an opaque key for subsequent updates.
     *
     * Returns `null` if the record could not be persisted.
     */
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    /**
     * Updates the record to COMPLETED status.
     *
     * @param key key returned by [recordAcquired], or a fallback key when that returned null.
     */
    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /**
     * Updates the record to FAILED status.
     *
     * @param errorType fully-qualified class name of the thrown exception, or null.
     * @param errorMessage sanitized exception message, or null.
     */
    suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )

    /**
     * Deletes records older than [cutoff], processing at most [limit] rows per call.
     *
     * The default implementation is a no-op.
     *
     * @return number of records actually deleted.
     */
    suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int = 0
}

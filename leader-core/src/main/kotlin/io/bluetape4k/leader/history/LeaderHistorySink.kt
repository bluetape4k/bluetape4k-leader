package io.bluetape4k.leader.history

import java.time.Instant

/**
 * SPI for persisting leader-lock lifecycle events.
 *
 * Implementations are expected to be **thread-safe**: a single sink instance is
 * shared across concurrent election calls on multiple threads.
 *
 * ## Behavior / Contract
 * - [recordAcquired] is called immediately after the lock is obtained, before the
 *   protected action starts.  It returns a [LeaderHistoryKey] that callers pass to
 *   [recordCompleted] or [recordFailed].
 * - If [recordAcquired] returns `null` (storage unavailable, duplicate key, etc.),
 *   the caller **must** construct a fallback key from `(lockName, token)` and pass it
 *   to [recordCompleted] / [recordFailed].  Implementations must handle a `null` [id]
 *   in the key gracefully (e.g. fall back to natural-key update).
 * - [recordCompleted] and [recordFailed] are best-effort: if the storage call fails,
 *   the exception is caught and logged by the recorder layer — the lock action result
 *   is never affected.
 * - [deleteOlderThan] has a no-op default; JDBC and R2DBC backends override it.
 *   MongoDB uses `deleteMany` without a `LIMIT` clause and ignores the [limit] parameter.
 * - Implementations must **not** throw [kotlinx.coroutines.CancellationException];
 *   use [SuspendLeaderHistorySink] for coroutine-aware sinks.
 *
 * ## Example
 * ```kotlin
 * val key: LeaderHistoryKey? = sink.recordAcquired(record)
 * val resolvedKey = key ?: LeaderHistoryKey(lockName = record.lockName, token = record.token)
 * try {
 *     val result = action()
 *     sink.recordCompleted(resolvedKey, Instant.now(), elapsedMs)
 * } catch (e: Exception) {
 *     sink.recordFailed(resolvedKey, Instant.now(), elapsedMs, e::class.qualifiedName, e.message)
 *     throw e
 * }
 * ```
 */
interface LeaderHistorySink {

    /**
     * Persists an ACQUIRED event and returns an opaque key for subsequent updates.
     *
     * Returns `null` if the record could not be persisted (e.g. storage error).
     */
    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    /**
     * Updates the record to COMPLETED status.
     *
     * @param key key returned by [recordAcquired], or a fallback key when that returned null.
     */
    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /**
     * Updates the record to FAILED status.
     *
     * @param errorType fully-qualified class name of the thrown exception, or null.
     * @param errorMessage sanitized exception message, or null.
     */
    fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )

    /**
     * Deletes records older than [cutoff], processing at most [limit] rows per call.
     *
     * The default implementation is a no-op.  JDBC and R2DBC backends override this
     * for bounded-batch retention jobs.  MongoDB ignores [limit] because `deleteMany`
     * does not support a `LIMIT` clause.
     *
     * @return number of records actually deleted.
     */
    fun deleteOlderThan(cutoff: Instant, limit: Int): Int = 0
}

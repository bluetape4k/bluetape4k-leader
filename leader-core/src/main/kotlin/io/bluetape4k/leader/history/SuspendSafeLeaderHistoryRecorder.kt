package io.bluetape4k.leader.history

import io.bluetape4k.leader.internal.truncateUtf8
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Fault-isolating wrapper around a [SuspendLeaderHistorySink] for coroutine-based electors.
 *
 * ## Behavior / Contract
 * - All sink calls are wrapped in a try/catch ladder that:
 *   1. Rethrows [CancellationException] (structured concurrency safety).
 *   2. Rethrows [InterruptedException] after restoring the thread interrupt flag
 *      (best-effort — sink implementations should use `runInterruptible {}` for blocking I/O).
 *   3. Catches all other [Exception] subtypes, logs a warning, and swallows.
 * - `CancellationException` from [recordFailed] is logged as a warning but **not**
 *   re-propagated (the CE has already been handled by the elector; recording it is best-effort).
 * - [recordAcquired] applies [sanitize] before forwarding to the sink.
 *
 * ## Audit isolation
 * Any [Exception] thrown by the sink is absorbed.  `Error` subtypes propagate freely.
 *
 * ## Security note on errorMessage
 * `sanitizeForLog()` is a log-injection defence, **not** a credential scrubber.
 * JDBC/driver exception messages may contain passwords or connection URLs.  Callers
 * are responsible for redacting credentials before passing an exception to [recordFailed].
 */
open class SuspendSafeLeaderHistoryRecorder(protected val sink: SuspendLeaderHistorySink) {

    companion object : KLogging()

    open suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        return try {
            sink.recordAcquired(sanitize(record))
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordAcquired — lockName=${record.lockName}" }
            null
        }
    }

    open suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordCompleted — key=$key" }
        }
    }

    open suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable?) {
        if (error is CancellationException) {
            log.warn { "Leader action was cancelled — recording as FAILED best-effort: key=$key" }
        }
        val errorType = error?.let { it::class.qualifiedName ?: it.javaClass.name }
        val errorMessage = error?.message?.sanitizeForLog()
            ?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES)
        try {
            sink.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            // best-effort: sink implementations should use runInterruptible {} for blocking IO
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordFailed — key=$key" }
        }
    }
}

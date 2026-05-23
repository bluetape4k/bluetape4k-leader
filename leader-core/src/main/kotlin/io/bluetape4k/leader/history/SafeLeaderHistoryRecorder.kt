package io.bluetape4k.leader.history

import io.bluetape4k.support.truncateUtf8
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Fault-isolating wrapper around a [LeaderHistorySink] for blocking (non-coroutine) electors.
 *
 * ## Behavior / Contract
 * - All sink calls are wrapped in a try/catch ladder that:
 *   1. Rethrows [kotlinx.coroutines.CancellationException] (structured concurrency safety).
 *   2. Rethrows [InterruptedException] after restoring the thread interrupt flag.
 *   3. Catches all other [Exception] subtypes, logs a warning, and swallows — the
 *      audit failure never propagates to the caller's action result.
 * - `CancellationException` from [recordFailed] is logged as a warning and then
 *   **not** re-propagated (the CE has already been handled by the elector; recording it
 *   as a failure is best-effort).
 * - [recordAcquired] applies [sanitize] before forwarding to the sink.
 * - Subclasses may override the `open fun` variants to inject additional behaviour
 *   (e.g. Micrometer counters).
 *
 * ## Audit isolation
 * Any [Exception] thrown by the sink is absorbed by this recorder.  `Error` subtypes
 * (e.g. [OutOfMemoryError]) are **not** caught — JVM-fatal errors propagate freely.
 *
 * ## Security note on errorMessage
 * `sanitizeForLog()` is a log-injection defence, **not** a credential scrubber.
 * JDBC/driver exception messages may contain passwords or connection URLs.  Callers
 * are responsible for redacting credentials before passing an exception to [recordFailed].
 */
open class SafeLeaderHistoryRecorder(protected val sink: LeaderHistorySink) {

    companion object : KLogging()

    open fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
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

    open fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
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

    open fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable?) {
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
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordFailed — key=$key" }
        }
    }
}

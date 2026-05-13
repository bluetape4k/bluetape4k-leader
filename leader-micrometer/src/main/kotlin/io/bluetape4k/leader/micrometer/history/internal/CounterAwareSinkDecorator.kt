package io.bluetape4k.leader.micrometer.history.internal

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Internal [LeaderHistorySink] decorator that increments Micrometer counters on sink events.
 *
 * Counter increment happens AFTER a successful delegate call or AFTER an [Exception] is caught —
 * it is never incremented in [CancellationException] or [InterruptedException] paths to avoid
 * counting structured-concurrency cancellations as sink failures.
 *
 * @param sinkSimpleName used as the `sink` tag value on all meters.
 */
internal class CounterAwareSinkDecorator(
    private val delegate: LeaderHistorySink,
    registry: MeterRegistry,
    sinkSimpleName: String,
) : LeaderHistorySink {

    companion object : KLogging()

    private val failureCounter: Counter = registry.counter(
        MicrometerNames.HISTORY_SINK_FAILURES,
        "sink", sinkSimpleName,
    )
    private val acquireMissingCounter: Counter = registry.counter(
        MicrometerNames.HISTORY_ACQUIRE_MISSING,
        "sink", sinkSimpleName,
    )

    override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        return try {
            val result = delegate.recordAcquired(record)
            if (result == null) acquireMissingCounter.increment()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            failureCounter.increment()
            throw e
        }
    }

    override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            delegate.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            failureCounter.increment()
            throw e
        }
    }

    override fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    ) {
        try {
            delegate.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            failureCounter.increment()
            throw e
        }
    }
}

package io.bluetape4k.leader.micrometer.history.internal

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Internal [SuspendLeaderHistorySink] decorator that increments Micrometer counters on sink events.
 *
 * [CancellationException] is rethrown **before** any counter increment to prevent
 * structured-concurrency cancellations from being counted as sink failures.
 *
 * @param sinkSimpleName used as the `sink` tag value on all meters.
 */
internal class CounterAwareSuspendSinkDecorator(
    private val delegate: SuspendLeaderHistorySink,
    registry: MeterRegistry,
    sinkSimpleName: String,
) : SuspendLeaderHistorySink {

    companion object : KLogging()

    private val failureCounter: Counter = registry.counter(
        MicrometerNames.HISTORY_SINK_FAILURES,
        "sink", sinkSimpleName,
    )
    private val acquireMissingCounter: Counter = registry.counter(
        MicrometerNames.HISTORY_ACQUIRE_MISSING,
        "sink", sinkSimpleName,
    )

    override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
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

    override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
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

    override suspend fun recordFailed(
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

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
 * `CounterAwareSinkDecorator`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property delegate Micrometer observability 계약에서 사용하는 속성입니다.
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

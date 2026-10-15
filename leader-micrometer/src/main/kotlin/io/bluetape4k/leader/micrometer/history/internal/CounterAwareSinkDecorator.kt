package io.bluetape4k.leader.micrometer.history.internal

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * 동기 `LeaderHistorySink` 호출을 감싸 sink 실패와 acquisition 결과 누락을 Micrometer counter로 기록합니다.
 *
 * @property delegate 실제 history 저장을 수행하는 sink입니다. 예외와 반환값은 이 객체가 변경하지 않고 그대로 전달합니다.
 */
internal class CounterAwareSinkDecorator(
    private val delegate: LeaderHistorySink,
    registry: MeterRegistry,
    sinkSimpleName: String,
): LeaderHistorySink {

    companion object: KLogging()

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

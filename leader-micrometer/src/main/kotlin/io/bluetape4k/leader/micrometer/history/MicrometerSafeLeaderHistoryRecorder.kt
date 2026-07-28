package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * `MicrometerSafeLeaderHistoryRecorder`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
open class MicrometerSafeLeaderHistoryRecorder(
    sink: LeaderHistorySink,
    meterRegistry: MeterRegistry,
) : SafeLeaderHistoryRecorder(
    CounterAwareSinkDecorator(
        delegate = sink,
        registry = meterRegistry,
        sinkSimpleName = sink::class.simpleName ?: "unknown",
    )
) {
    companion object : KLogging()
}

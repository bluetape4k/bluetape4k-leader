package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSuspendSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * `MicrometerSuspendSafeLeaderHistoryRecorder`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
open class MicrometerSuspendSafeLeaderHistoryRecorder(
    sink: SuspendLeaderHistorySink,
    meterRegistry: MeterRegistry,
) : SuspendSafeLeaderHistoryRecorder(
    CounterAwareSuspendSinkDecorator(
        delegate = sink,
        registry = meterRegistry,
        sinkSimpleName = sink::class.simpleName ?: "unknown",
    )
) {
    companion object : KLogging()
}

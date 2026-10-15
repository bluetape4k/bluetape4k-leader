package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSuspendSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * suspend history sink를 Micrometer counter로 감싸는 `SuspendSafeLeaderHistoryRecorder`입니다.
 *
 * sink 예외는 원래 호출자에게 다시 전달하면서 실패 횟수와 acquisition key 누락을 기록합니다.
 */
open class MicrometerSuspendSafeLeaderHistoryRecorder(
    sink: SuspendLeaderHistorySink,
    meterRegistry: MeterRegistry,
): SuspendSafeLeaderHistoryRecorder(
    CounterAwareSuspendSinkDecorator(
        delegate = sink,
        registry = meterRegistry,
        sinkSimpleName = sink::class.simpleName ?: "unknown",
    )
) {
    companion object: KLogging()
}

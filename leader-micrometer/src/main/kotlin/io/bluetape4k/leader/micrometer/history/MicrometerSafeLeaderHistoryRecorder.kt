package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * 동기 history sink를 Micrometer counter로 감싸는 `SafeLeaderHistoryRecorder`입니다.
 *
 * sink 예외는 원래 호출자에게 다시 전달하면서 실패 횟수와 acquisition key 누락을 기록합니다.
 */
open class MicrometerSafeLeaderHistoryRecorder(
    sink: LeaderHistorySink,
    meterRegistry: MeterRegistry,
): SafeLeaderHistoryRecorder(
    CounterAwareSinkDecorator(
        delegate = sink,
        registry = meterRegistry,
        sinkSimpleName = sink::class.simpleName ?: "unknown",
    )
) {
    companion object: KLogging()
}

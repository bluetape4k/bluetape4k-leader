package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSuspendSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * [SuspendSafeLeaderHistoryRecorder] with Micrometer counter instrumentation for coroutine electors.
 *
 * Counter instrumentation is applied via [CounterAwareSuspendSinkDecorator] injected at
 * construction time; no method overrides are needed.  [kotlinx.coroutines.CancellationException]
 * is rethrown before any counter increment.
 *
 * ## Example
 * ```kotlin
 * val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(
 *     sink = mongoLeaderHistorySink,
 *     meterRegistry = Metrics.globalRegistry,
 * )
 * ```
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

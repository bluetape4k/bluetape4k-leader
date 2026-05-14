package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.internal.CounterAwareSinkDecorator
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry

/**
 * [SafeLeaderHistoryRecorder] with Micrometer counter instrumentation.
 *
 * Counter instrumentation is applied via [CounterAwareSinkDecorator] injected at construction
 * time; no method overrides are needed.  The decorator increments counters only on normal
 * returns and swallowed exceptions — [kotlinx.coroutines.CancellationException] and
 * [InterruptedException] are rethrown before any counter increment.
 *
 * ## Example
 * ```kotlin
 * val recorder = MicrometerSafeLeaderHistoryRecorder(
 *     sink = jdbcLeaderHistorySink,
 *     meterRegistry = Metrics.globalRegistry,
 * )
 * ```
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

package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import kotlin.time.Duration

/**
 * Leader Aspect callback SPI — implemented by callers for metrics / tracing / custom hooks.
 *
 * Provides 6 legacy overloads (no context) and 6 context-bearing overloads (with [LeaderAopMetricsContext]).
 * Implementations that need leader ID information should override the context variants.
 *
 * ## Multi-bean injection
 * The aspect fan-outs to all registered [LeaderAopMetricsRecorder] beans via
 * `ObjectProvider<List<...>>` — 0 (NoOp) to N simultaneous registrations are supported.
 *
 * ## Isolation
 * Each recorder is isolated with `runCatching` — a throw from one recorder does not affect
 * the leader body or other recorders.
 *
 * ## Best-effort
 * `onLockNotAcquired(CONTENTION)` cannot distinguish a null body result from a non-elected result
 * due to core SPI limitations — precise separation is tracked in issue #85.
 *
 * ## Call order
 * - elected: `onLockAttempt` → `onLockAcquired` → `onTaskStarted` → `onTaskFinished`
 * - skipped (CONTENTION): `onLockAttempt` → `onLockNotAcquired(CONTENTION)`
 * - failed: `onLockAttempt` → `onLockAcquired` → `onTaskStarted` → `onTaskFailed`
 * - skipped (BACKEND_ERROR, SKIP mode): `onLockAttempt` → `onLockNotAcquired(BACKEND_ERROR)`
 *
 * ## Micrometer integration
 * See [io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder] for a full implementation.
 */
interface LeaderAopMetricsRecorder {

    // =========================================================================
    // Legacy overloads (no context) — 6 methods
    // =========================================================================

    /** Lock acquisition attempt — called immediately before `pjp.proceed()`. */
    fun onLockAttempt(name: String, options: LeaderElectionOptions) {}

    /** Lock acquired — called before entering the body. [acquireElapsed] = attempt-to-acquired elapsed time. */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {}

    /** Lock not acquired — [reason] classifies the cause. */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {}

    /** Task body starting — called immediately after `onLockAcquired`. */
    fun onTaskStarted(name: String) {}

    /** Task body completed normally. [executionTime] = attempt-to-completion elapsed time. */
    fun onTaskFinished(name: String, executionTime: Duration) {}

    /** Task body or backend threw an exception. */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {}

    // =========================================================================
    // Context-bearing overloads — 6 methods
    // Default implementations drop context via LeaderRecorderContextDropLog and
    // delegate to the legacy overloads. Override to capture leader ID information.
    // =========================================================================

    /** Context-bearing variant — default drops [context] and delegates to [onLockAttempt]. */
    fun onLockAttempt(name: String, options: LeaderElectionOptions, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAttempt(name, options)
    }

    /** Context-bearing variant — default drops [context] and delegates to [onLockAcquired]. */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAcquired(name, options, acquireElapsed)
    }

    /** Context-bearing variant — default drops [context] and delegates to [onLockNotAcquired]. */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockNotAcquired(name, options, reason)
    }

    /** Context-bearing variant — default drops [context] and delegates to [onTaskStarted]. */
    fun onTaskStarted(name: String, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskStarted(name)
    }

    /** Context-bearing variant — default drops [context] and delegates to [onTaskFinished]. */
    fun onTaskFinished(name: String, executionTime: Duration, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFinished(name, executionTime)
    }

    /** Context-bearing variant — default drops [context] and delegates to [onTaskFailed]. */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFailed(name, executionTime, throwable)
    }

    /** No-op default implementation — used to enable fast-path when no recorder beans are registered. */
    object NoOp : LeaderAopMetricsRecorder
}

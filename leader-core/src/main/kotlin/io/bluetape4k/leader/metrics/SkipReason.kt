package io.bluetape4k.leader.metrics

/**
 * Reason passed to `LeaderAopMetricsRecorder.onLockNotAcquired` when a leader is not elected.
 *
 * [#85] With the introduction of the `LeaderRunResult` sealed SPI, `CONTENTION` is now accurate —
 * the `elected` flag inside `runIfLeaderResult` clearly distinguishes a body `null` return from not being elected.
 */
enum class SkipReason {
    /** Lock not acquired within waitTime. */
    CONTENTION,

    /** Backend exception occurred and absorbed in SKIP mode. */
    BACKEND_ERROR,

    /**
     * Lock not acquired (contention) or backend exception occurred, then body runs without a lock in `FAIL_OPEN_RUN` mode.
     * After the `onLockNotAcquired` event is published, `onTaskFinished` is published if the body completes normally.
     */
    FAIL_OPEN_FORCED,
}

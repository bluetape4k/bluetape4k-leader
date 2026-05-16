package io.bluetape4k.leader.annotation

/**
 * Aspect failure policy when leader lock acquisition fails (backend exception or contention).
 *
 * ## Options
 * - [RETHROW] (default): wraps backend exceptions in [io.bluetape4k.leader.LeaderElectionException] /
 *   `LeaderGroupElectionException` and propagates to the caller.
 *   Generalizes the message to prevent leaking host/credentials infrastructure details. Preserves cause.
 * - [SKIP]: absorbs lock acquisition failures or backend exceptions and returns `null` (equivalent to ShedLock skip).
 * - [FAIL_OPEN_RUN]: runs the body without a lock when acquisition fails or a backend exception occurs.
 *   For circuit-breaker scenarios where continuing execution is preferable to stopping on lock system failure.
 *   **Warning**: multiple instances may execute the body concurrently — use only with idempotent actions.
 *
 * ## Independent of body exceptions
 * This enum handles only lock acquisition failure. Exceptions thrown by the user body (`pjp.proceed()`)
 * are always propagated as-is regardless of `failureMode` (no wrapping).
 */
enum class LeaderAspectFailureMode {
    /**
     * Sentinel — uses the `LeaderAopProperties.failureMode` global default.
     * Used in annotations only. The `LeaderAopProperties.failureMode` default is [RETHROW].
     */
    INHERIT,

    /** Wraps the backend exception and propagates it to the caller. */
    RETHROW,

    /** Absorbs lock acquisition failures or backend exceptions and returns `null` (equivalent to ShedLock skip). */
    SKIP,

    /**
     * Runs the body without a lock when acquisition fails or a backend exception occurs (fail-open).
     *
     * - Skipped due to contention → emits `onLockNotAcquired(FAIL_OPEN_FORCED)` then runs the body.
     * - Backend exception → logs a warning then runs the body.
     *
     * **Warning**: multiple instances may run the body concurrently since no lock is held. Use only with idempotent actions.
     */
    FAIL_OPEN_RUN,
}

package io.bluetape4k.leader

import java.time.Instant

/**
 * Detailed result of [LeaderLockHandle.Real.extend] / [LeaderLockHandle.Real.extendSuspend].
 *
 * The `Boolean` API ([LockExtender.extendActiveLock]) is equivalent to an `is Extended` conversion.
 * Use [LockExtender.extendActiveLockDetailed] when you need a classified result for operational visibility.
 *
 * ## Classification
 * - [Extended] — backend extend succeeded. `observedExpireAt` is a best-effort value (accuracy varies by backend).
 * - [NotHeld] — token mismatch / lease expired / takeover occurred — lock is no longer held.
 * - [WrongThread] — called from a thread different from the acquiring thread on a Redisson thread-bound lock.
 * - [BackendError] — transient (retryable) or non-transient backend error. `cause` must be an [Exception] (FATAL [Error] is blocked).
 *
 * ## Boolean conversion policy
 * The `Boolean` returned by `LockExtender.extendActiveLock(d)`:
 * - [Extended] → `true`
 * - [NotHeld], [WrongThread] → `false` (WARN log + metric)
 * - [BackendError] (transient) → `false` (WARN log + metric)
 * - [BackendError] (non-transient) → throws (caller's responsibility)
 *
 * ## Example
 * ```kotlin
 * when (val outcome = LockExtender.extendActiveLockDetailed(60.seconds)) {
 *     is ExtendOutcome.Extended -> log.info { "lease extended until ${outcome.observedExpireAt}" }
 *     is ExtendOutcome.NotHeld -> rollbackWork()
 *     is ExtendOutcome.WrongThread -> log.warn { "Redisson thread-bound — dispatched from wrong thread" }
 *     is ExtendOutcome.BackendError -> retry(outcome.cause)
 * }
 * ```
 */
sealed interface ExtendOutcome {

    /**
     * Extend succeeded.
     *
     * @property observedExpireAt **best-effort** new expiry time. Accuracy by backend:
     * - Lettuce / Hazelcast / Local: uses server-side time → accurate
     * - Redisson: Redisson internal atomic — may use client clock → ±50ms
     * - MongoDB: server-side `$$NOW` aggregation → accurate
     * - Exposed JDBC/R2DBC: DB server time (`now()` SQL) → accurate
     * - ZooKeeper: no TTL concept — `Instant.MAX` (session-held liveness passthrough)
     *
     * Do not use as a precise deadline — intended for observability/logging only.
     */
    data class Extended(val observedExpireAt: Instant) : ExtendOutcome

    /** Token mismatch / lease expired / takeover — lock is no longer held. */
    data object NotHeld : ExtendOutcome

    /** Called from a thread different from the acquiring thread on a Redisson thread-bound lock. */
    data object WrongThread : ExtendOutcome

    /**
     * Backend error. Use `BackendErrorClassifier` to classify as transient (retryable) or non-transient.
     *
     * `cause` must be an [Exception] — wrapping FATAL [Error] ([OutOfMemoryError], [StackOverflowError],
     * [LinkageError], etc.) is forbidden; propagate them directly.
     */
    data class BackendError(val cause: Exception) : ExtendOutcome

    /** Shortcut for Boolean API conversion. */
    val isExtended: Boolean get() = this is Extended
}

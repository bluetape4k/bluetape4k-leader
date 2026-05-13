package io.bluetape4k.leader.history

/**
 * Four-state lifecycle of a leader lock history record.
 *
 * ## Behavior / Contract
 * - [ACQUIRED]: the lock was obtained; the protected action has not yet finished.
 * - [COMPLETED]: the action finished without error and the lock was released normally.
 * - [FAILED]: the action threw an exception (or a pre-action guard failed).
 * - [EXPIRED]: the record's `lockedUntil` has passed while the status is still [ACQUIRED],
 *   indicating a possible crash or lease timeout.  Transition to [EXPIRED] is computed
 *   on read via [io.bluetape4k.leader.history.effectiveStatus] and is **not persisted**
 *   by the sink — sweeper-based status update is out-of-scope for v1.
 *
 * ## Example
 * ```kotlin
 * val effective = record.effectiveStatus()
 * if (effective == LeaderHistoryStatus.EXPIRED) {
 *     alertStaleRecord(record)
 * }
 * ```
 */
enum class LeaderHistoryStatus {
    ACQUIRED,
    COMPLETED,
    FAILED,
    EXPIRED,
}

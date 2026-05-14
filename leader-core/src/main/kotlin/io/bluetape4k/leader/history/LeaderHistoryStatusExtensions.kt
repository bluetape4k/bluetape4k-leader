package io.bluetape4k.leader.history

import java.time.Instant

/**
 * Computes the effective [LeaderHistoryStatus] of this record at the given [now] instant.
 *
 * ## Behavior / Contract
 * - If [LeaderLockHistoryRecord.status] is non-null, it is returned as-is (the record
 *   has reached a terminal state: [LeaderHistoryStatus.COMPLETED] or [LeaderHistoryStatus.FAILED]).
 * - If [LeaderLockHistoryRecord.status] is null, the record is still in the
 *   [LeaderHistoryStatus.ACQUIRED] state.  When [LeaderLockHistoryRecord.lockedUntil]
 *   is strictly before [now], the record is considered [LeaderHistoryStatus.EXPIRED] —
 *   indicating a likely crash or unclean shutdown.
 * - [LeaderHistoryStatus.EXPIRED] is a **computed** virtual state; it is never
 *   persisted by the sink.  Sweeper-based persistence is out-of-scope for v1.
 *
 * ## Clock skew note
 * [now] defaults to `Instant.now()` on the application JVM.  If the application
 * clock and the lock-backend clock diverge, a record may appear [LeaderHistoryStatus.EXPIRED]
 * earlier or later than expected.  Pass an explicit [now] in tests to avoid flakiness.
 *
 * ## Example
 * ```kotlin
 * val effective = record.effectiveStatus()
 * if (effective == LeaderHistoryStatus.EXPIRED) {
 *     alertStaleRecord(record)
 * }
 * ```
 */
fun LeaderLockHistoryRecord.effectiveStatus(now: Instant = Instant.now()): LeaderHistoryStatus {
    if (status != null) return status
    return if (lockedUntil.isBefore(now)) LeaderHistoryStatus.EXPIRED else LeaderHistoryStatus.ACQUIRED
}

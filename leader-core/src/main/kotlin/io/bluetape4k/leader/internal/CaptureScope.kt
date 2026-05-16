package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.coroutines.LockHandleElement

/**
 * Helper that safely wraps [LeaderLockHandleCapture] set/clear inside a sync elector's `runIfLeader`.
 *
 * ## Purpose (Step 3-P R1 mitigation)
 * If each elector calls [LeaderLockHandleCapture.set] and [LeaderLockHandleCapture.clear] directly,
 * a missing try/finally causes a ThreadLocal leak. Using this helper in all electors converts
 * **per-backend discipline → structural enforcement**.
 *
 * AC: elector implementations do not call [LeaderLockHandleCapture.set] directly.
 *
 * ## Example
 * ```kotlin
 * // inside a backend elector's runIfLeader:
 * runWithCapture(handle) {
 *     action()  // aspect's wrapped action — capture poll is available
 * }
 * ```
 *
 * Suspend electors do not use this capture scope because a dispatcher hop may cause the ThreadLocal
 * set/clear to run on different threads. Suspend lock handle propagation uses [LockHandleElement] only.
 */
internal object CaptureScope {

    /** Sync variant — set → action → clear on the same thread. */
    inline fun <T> runWithCapture(handle: LeaderLockHandle.Real, action: () -> T): T {
        LeaderLockHandleCapture.set(handle)
        try {
            return action()
        } finally {
            LeaderLockHandleCapture.clear()
        }
    }
}

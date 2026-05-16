package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * ThreadLocal for passing a handle from the elector to the aspect.
 *
 * ## Strict Invariant (Step 2-R R10)
 * - Sync group elector calls [set] on the same thread **immediately before** invoking the action after acquire.
 * - Sync group aspect calls [poll] as the **first statement** of the action lambda.
 * - **Missing capture (poll returns null) → throws `CaptureInvariantException`** ("elector did not capture handle — bug").
 *   Silent fallback to FailOpen is strictly forbidden (Codex F10 / SF3).
 * - Splitting set/poll across a virtual thread / dispatcher hop is forbidden.
 *
 * ## Usage Recommendation
 * Do not call directly; use [CaptureScope.runWithCapture] in sync electors.
 * Suspend electors must use `LockHandleElement`-based coroutine context propagation only,
 * because a dispatcher hop may cause the ThreadLocal set/clear to run on different threads.
 */
internal object LeaderLockHandleCapture {

    private val tl: ThreadLocal<LeaderLockHandle.Real?> = ThreadLocal()

    fun set(handle: LeaderLockHandle.Real) {
        tl.set(handle)
    }

    /** Retrieves the value from the ThreadLocal and immediately clears it. */
    fun poll(): LeaderLockHandle.Real? {
        val handle = tl.get()
        tl.remove()
        return handle
    }

    fun clear() {
        tl.remove()
    }
}

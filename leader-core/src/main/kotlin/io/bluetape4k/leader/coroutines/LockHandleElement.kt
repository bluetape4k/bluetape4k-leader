package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderLockHandle
import kotlin.coroutines.CoroutineContext

/**
 * `CoroutineContext.Element` that carries the active lock handle in suspend / Mono contexts.
 *
 * **Separate element** from the existing [LeaderElectionInfo] — preserves binary compatibility (Codex F5 / Type T8).
 * The aspect pushes both elements simultaneously using `withContext(LeaderElectionInfo(...) + LockHandleElement(...))`.
 *
 * ## Behavior / Contract
 * - The `handle` property is `internal` — external callers must use [io.bluetape4k.leader.LockAssert] /
 *   [io.bluetape4k.leader.LockExtender] APIs only. Direct access to handle metadata (token, slotId, etc.) is blocked (R3-F12).
 * - The public companion `Key` allows `currentCoroutineContext()[LockHandleElement]` checks — only element presence is exposed.
 *
 * ## Example
 * ```kotlin
 * // inside the aspect:
 * withContext(LeaderElectionInfo("job-A", true) + LockHandleElement(handle)) {
 *     userBody()  // LockAssert.assertLockedSuspend() / LockExtender.extendActiveLockSuspend(d) can be called inside
 * }
 * ```
 */
data class LockHandleElement(
    internal val handle: LeaderLockHandle,
): CoroutineContext.Element {

    companion object Key: CoroutineContext.Key<LockHandleElement>

    override val key: CoroutineContext.Key<*> get() = Key
}

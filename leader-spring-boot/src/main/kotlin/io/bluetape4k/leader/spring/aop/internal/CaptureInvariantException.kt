package io.bluetape4k.leader.spring.aop.internal

/**
 * Thrown by the AOP aspect when it detects a handle-capture invariant violation between the elector and the aspect.
 *
 * ## Invariant
 * The sync group elector's `CaptureScope.runWithCapture` calls `LeaderLockHandleCapture.set(handle)`
 * just before invoking the action. The sync aspect receives the handle via `AopScopeAccess.pollCapture()`
 * as the first statement of the action lambda. The suspend group elector does not use ThreadLocal capture;
 * instead it propagates the handle into the coroutine context via `LockHandleElement`.
 *
 * **Single elector does not use CaptureScope, so `pollCapture() == null` is normal** — this exception is
 * thrown only on capture failure specific to the group elector.
 *
 * Silent fail-open conversion is strictly forbidden (Codex F10 / SF3).
 */
internal class CaptureInvariantException(message: String) : IllegalStateException(message)

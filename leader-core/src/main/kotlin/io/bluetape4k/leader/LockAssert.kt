package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import kotlin.coroutines.coroutineContext

/**
 * Asserts that the current context is executing inside an active `@LeaderElection` / `@LeaderGroupElection` body.
 *
 * Provides the same usage experience as ShedLock's `LockAssert.assertLocked()`.
 *
 * ## Behavior / Contract
 * - No active lock state → [IllegalStateException]
 * - Fail-open sentinel scope (`failureMode = FAIL_OPEN_RUN` with backend failure) → [IllegalStateException]
 *   (the fail-open body runs without a lock)
 * - Reentrant entry passes through as long as the outer scope is not a sentinel
 * - `is FailOpen` branch on the sealed [LeaderLockHandle] is compiler-enforced exhaustive check
 *
 * ## Cross-context behavior (suspend → sync API call)
 * **Call [assertLockedSuspend] in suspend contexts**. Calling sync [assertLocked] inside suspend only checks
 * the carrier thread's ThreadLocal → risk of exposing an unrelated handle (R7).
 *
 * ## ⚠️ Reactor non-suspend operators not supported (Step 3-P R5)
 * Inside non-suspend Reactor operators such as `.map { LockAssert.assertLocked() }` or `.filter { ... }`,
 * neither ThreadLocal nor `CoroutineContext` is present → throws.
 * **Use `.flatMap { mono { LockAssert.assertLockedSuspend() } }` instead**.
 *
 * ## Example
 * ```kotlin
 * @LeaderElection(name = "report-job")
 * fun runReport() {
 *     LockAssert.assertLocked()       // passes
 *     // ... critical work ...
 * }
 *
 * fun runOutsideAnnotation() {
 *     LockAssert.assertLocked()       // throws IllegalStateException
 * }
 * ```
 */
object LockAssert : KLogging() {

    /**
     * Asserts that the current thread has an active lock scope and it is not fail-open.
     *
     * @throws IllegalStateException if there is no active scope or if the scope is a fail-open sentinel
     */
    @JvmStatic
    fun assertLocked() {
        val handle = LockStateHolder.peekSync()
            ?: error("LockAssert.assertLocked() called outside an active @LeaderElection / @LeaderGroupElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked() — current scope is fail-open (no real lock held). lockName=${handle.lockName}"
        }
    }

    /**
     * Asserts that there is an active lock scope for the given lock name and it is not fail-open.
     *
     * @param lockName the lock name to check
     * @throws IllegalStateException if there is no active scope for that name or if the scope is a fail-open sentinel
     */
    @JvmStatic
    fun assertLocked(lockName: String) {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: error("LockAssert.assertLocked('$lockName') — no active scope with this lock")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked('$lockName') — current scope is fail-open"
        }
    }

    /**
     * Returns whether the current thread holds a real lock, without throwing.
     *
     * @return `true` if there is an active [LeaderLockHandle.Real] scope, `false` if absent or fail-open
     */
    @JvmStatic
    fun isLocked(): Boolean {
        val handle = LockStateHolder.peekSync() ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * Returns whether a real lock is held for the given lock name, without throwing.
     *
     * @param lockName the lock name to check
     * @return `true` if there is an active [LeaderLockHandle.Real] scope for that name, `false` if absent or fail-open
     */
    @JvmStatic
    fun isLocked(lockName: String): Boolean {
        val handle = LockStateHolder.peekSyncMatching(lockName) ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * Suspend variant — checks only `coroutineContext[LockHandleElement]`.
     *
     * **ThreadLocal fallback removed (R7 / Codex F13)** — prevents sync ThreadLocal leakage.
     * Calling sync `assertLocked()` inside suspend only checks the carrier thread's unrelated lock handle.
     *
     * @throws IllegalStateException if there is no active scope or if the scope is a fail-open sentinel
     */
    suspend fun assertLockedSuspend() {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend() called outside an active @LeaderElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend() — current scope is fail-open. lockName=${handle.lockName}"
        }
    }

    /**
     * Suspend variant — checks `coroutineContext[LockHandleElement]` for the given lock name.
     *
     * @param lockName the lock name to check
     * @throws IllegalStateException if there is no active scope, the lock name does not match, or the scope is a fail-open sentinel
     */
    suspend fun assertLockedSuspend(lockName: String) {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend('$lockName') — no active scope")
        check(handle.lockName == lockName) {
            "LockAssert.assertLockedSuspend('$lockName') — active lock is '${handle.lockName}'"
        }
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend('$lockName') — current scope is fail-open"
        }
    }

    /**
     * Returns whether a real lock is held in the current coroutine context, without throwing.
     *
     * @return `true` if an active [LeaderLockHandle.Real] is present in the coroutineContext, `false` if absent or fail-open
     */
    suspend fun isLockedSuspend(): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * Returns whether a real lock is held for the given lock name in the coroutine context, without throwing.
     *
     * @param lockName the lock name to check
     * @return `true` if an active [LeaderLockHandle.Real] for that name is present in the coroutineContext, `false` if absent or fail-open
     */
    suspend fun isLockedSuspend(lockName: String): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real && handle.lockName == lockName
    }
}

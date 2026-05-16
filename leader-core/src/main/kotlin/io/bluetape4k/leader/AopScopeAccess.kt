package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LeaderLockHandleCapture
import io.bluetape4k.leader.internal.LockStateHolder

/**
 * Public bridge for the AOP aspect (`leader-spring-boot`) to access `internal` scope management symbols in `leader-core`.
 *
 * ## Usage Restrictions
 * - **AOP aspect-only API** — do not use directly in general application code.
 * - In general code, use only [LockAssert] / [LockExtender].
 *
 * ## Exposed Scope
 * | Symbol | Purpose |
 * |--------|---------|
 * | [peekSyncMatching] | sync branch reentrant peek (checks whether the same lockName is held) |
 * | [withPushedSync] | sync branch handle push/pop — manages LockStateHolder before and after body execution |
 * | [pollCapture] | sync group elector → aspect handle delivery receiver (after CaptureScope.runWithCapture) |
 * | [createFailOpen] | creates a fail-open sentinel handle |
 * | [createLockHandleElement] | creates a LockHandleElement to inject into CoroutineContext |
 */
object AopScopeAccess {

    /**
     * Peeks the handle matching [lockName] from the current thread's lock stack.
     *
     * Used by the aspect to determine whether the call is reentrant before entering the sync branch.
     */
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        LockStateHolder.peekSyncMatching(lockName)

    /**
     * Pushes [handle] onto the sync lock stack, executes [block], then pops it.
     *
     * Prevents leaks via try/finally. Used when injecting a fail-open sentinel or reentrant passthrough handle.
     *
     * Note: cannot be `inline` because this is a public bridge — push/pop are implemented explicitly
     * instead of delegating directly to the internal `LockStateHolder.withPushed` inline function.
     */
    fun <R> withPushedSync(handle: LeaderLockHandle, block: () -> R): R {
        LockStateHolder.push(handle)
        try {
            return block()
        } finally {
            LockStateHolder.pop()
            LockStateHolder.cleanup()
        }
    }

    /**
     * Retrieves the handle from the [LeaderLockHandleCapture] ThreadLocal and immediately clears it.
     *
     * The aspect receives the value set by `CaptureScope.runWithCapture` in the sync group elector.
     * Single electors do not capture, so `null` is normal — do not use CaptureInvariantException.
     * Suspend group electors do not use ThreadLocal capture; they use only [createLockHandleElement].
     */
    fun pollCapture(): LeaderLockHandle.Real? = LeaderLockHandleCapture.poll()

    /**
     * Backend module only — sets the handle that the aspect will poll immediately after the sync group elector acquires.
     *
     * ⚠️ Do not call directly from application code — sync group electors such as `leader-redis-lettuce`,
     * `leader-redis-redisson`, and `leader-mongodb` must guarantee the
     * `setCapture` → action → [clearCapture] sequence on the same thread.
     *
     * Suspend group electors must not call this because a dispatcher hop may cause the ThreadLocal
     * set/clear to run on different threads. Instead, propagate the handle to coroutine context
     * via [createLockHandleElement].
     *
     * Typical usage with try/finally:
     *
     * ```kotlin
     * AopScopeAccess.setCapture(handle)
     * try {
     *     action()  // the aspect calls pollCapture as its first statement
     * } finally {
     *     AopScopeAccess.clearCapture()
     * }
     * ```
     *
     * Single electors do not need capture, so this is not called for them.
     */
    fun setCapture(handle: LeaderLockHandle.Real) {
        LeaderLockHandleCapture.set(handle)
    }

    /**
     * Backend module only — explicitly clears the sync group ThreadLocal set by [setCapture].
     *
     * Call in the `try/finally` finally block to prevent ThreadLocal leaks.
     * Do not call from suspend group electors.
     */
    fun clearCapture() {
        LeaderLockHandleCapture.clear()
    }

    /**
     * Creates a fail-open sentinel [LeaderLockHandle.FailOpen].
     *
     * Pushes it onto the stack so that [LockAssert] / [LockExtender] can recognize
     * the fail-open scope when the body executes in the `failureMode = FAIL_OPEN_RUN` branch.
     */
    fun createFailOpen(identity: LockIdentity): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity)

    /**
     * Creates a passthrough copy of [LeaderLockHandle.Real] with an incremented reentry depth.
     *
     * When the same lock is re-entered, the aspect pushes a handle with the new depth.
     */
    fun incrementReentryDepth(handle: LeaderLockHandle.Real): LeaderLockHandle.Real =
        handle.withReentryDepth(handle.reentryDepth + 1)

    /**
     * Creates a [LockHandleElement] for the aspect to inject into the coroutine context.
     *
     * Used in the suspend / Mono branch with the pattern
     * `withContext(LeaderElectionInfo(...) + createLockHandleElement(handle))`.
     */
    fun createLockHandleElement(handle: LeaderLockHandle): LockHandleElement =
        LockHandleElement(handle)

    /**
     * Used by tests or the aspect to create a synthetic `LeaderLockHandle.Real`.
     *
     * Creates a handle to push into `LockStateHolder` when writing reentrant unit tests
     * without a real backend. Production aspect code uses only handles created by electors.
     */
    fun createSyntheticReal(
        lockName: String,
        factoryBeanName: String,
        token: String = SYNTHETIC_SINGLE_TOKEN,
    ): LeaderLockHandle.Real {
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = factoryBeanName,
        )
        return LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = io.bluetape4k.leader.internal.NoopExtendDelegate,
        )
    }

    /**
     * Creates a group `LeaderLockHandle.Real` for testing, holding `slotId` and `groupParams`.
     *
     * Used to create a handle to push onto the stack in reentrant unit tests for the `@LeaderGroupElection` aspect.
     */
    fun createSyntheticGroupReal(
        lockName: String,
        factoryBeanName: String,
        maxLeaders: Int,
        slotId: String = SYNTHETIC_DEFAULT_SLOT,
        token: String = SYNTHETIC_GROUP_TOKEN,
    ): LeaderLockHandle.Real {
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = factoryBeanName,
            groupParams = LockIdentity.GroupParams(maxLeaders = maxLeaders),
        )
        return LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = System.nanoTime(),
            slotId = slotId,
            extendDelegate = io.bluetape4k.leader.internal.NoopExtendDelegate,
        )
    }

    /** Synthetic single handle default token — do not depend on this in production. */
    private const val SYNTHETIC_SINGLE_TOKEN = "test-token"

    /** Synthetic group handle default token — do not depend on this in production. */
    private const val SYNTHETIC_GROUP_TOKEN = "test-group-token"

    /** Synthetic group handle default slotId. */
    private const val SYNTHETIC_DEFAULT_SLOT = "0"
}

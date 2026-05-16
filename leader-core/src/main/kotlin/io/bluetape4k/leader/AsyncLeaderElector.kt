package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Defines the contract for asynchronous leader election execution.
 *
 * ## Behavior / Contract
 * - Implementations execute `action` only when leadership is successfully acquired for `lockName`.
 * - The completion timing of the returned [CompletableFuture] depends on when leadership is acquired and `action` completes.
 * - Exception model for election failure or action errors follows the implementation policy.
 * - The default [Executor] uses the [VirtualThreadExecutor] singleton.
 *   Virtual Threads yield the carrier thread when blocking on lock waits or futures, making them suitable for async election.
 *   Requires Java 21 or later.
 *
 * ```kotlin
 * val future = election.runAsyncIfLeader("batch-lock") { CompletableFuture.completedFuture("ok") }
 * // future.join() == "ok"
 * ```
 */
interface AsyncLeaderElector: LeaderElectionState {

    /**
     * Executes the asynchronous [action] when leadership is successfully acquired.
     *
     * ## Behavior / Contract
     * - [executor] may be used by the implementation on the leader election/action execution path.
     * - The success/failure state of the future returned by [action] is reflected in the result future.
     * - [lockName] validation rules follow the implementation policy.
     * - The default [executor] is the [VirtualThreadExecutor] singleton, suitable for blocking work.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job", action = { CompletableFuture.completedFuture(1) }).join()
     * // result == 1 (leader acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param executor the [Executor] to use for async execution. Default is the [VirtualThreadExecutor] singleton.
     * @param action the async action to run when leadership is acquired
     * @return [CompletableFuture] carrying the action result, or completing with `null` when leadership was not acquired
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * Runs [action] asynchronously if elected for [slot], stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * This default delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog] on first use per `(implClass, slot)` pair.
     * Backend implementations MUST override this method to stamp [slot.leaderId] into
     * [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to the action result, or `null` when not elected.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, executor, action)
    }

    /**
     * Returns [LeaderRunResult] for the async slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked because the bridge cannot verify that the backend actually used this slot's identity.
     *
     * Backend implementations MUST override BOTH slot variants
     * ([runAsyncIfLeader] and [runAsyncIfLeaderResult]) to carry [slot.leaderId] through to
     * [LeaderRunResult.Elected.leaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to [LeaderRunResult.Elected] (action ran) or
     *   [LeaderRunResult.Skipped] (not elected).
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        return runAsyncIfLeader(slot.lockName, executor) {
            elected.set(true)
            action()
        }.handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)
}

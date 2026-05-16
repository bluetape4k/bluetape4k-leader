package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Defines the contract for Virtual Thread-based leader election execution.
 *
 * ## Difference from [AsyncLeaderElector]
 * - `action` is a `() -> T` lambda that returns the result directly without wrapping in [java.util.concurrent.CompletableFuture].
 * - The return type is [VirtualFuture], and the result is consumed via `await()` or `toCompletableFuture()`.
 * - Internally uses Virtual Threads, so carrier threads are released during lock waiting or I/O blocking.
 * - Requires Java 21 or later.
 *
 * ## Behavior / Contract
 * - Implementations execute `action` only when the leader is successfully acquired for `lockName`.
 * - Exceptions from `action` are propagated to the caller when [VirtualFuture.await] is called.
 * - The exception model for leader election failure / execution error follows the implementation policy.
 *
 * ```kotlin
 * val future = election.runAsyncIfLeader("batch-lock") { "ok" }
 * val result = future.await()  // "ok"
 * ```
 */
interface VirtualThreadLeaderElector: LeaderElectionState {

    /**
     * Executes [action] asynchronously on a Virtual Thread when leader acquisition succeeds.
     *
     * ## Behavior / Contract
     * - `action` returns the result directly; [java.util.concurrent.CompletableFuture] wrapping is not needed.
     * - Use [VirtualFuture.await] to wait synchronously or [VirtualFuture.toCompletableFuture] to convert.
     * - The lock is safely released even when an exception occurs during `action` execution.
     * - Validation rules for `lockName` follow the implementation policy.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job-lock") { computeResult() }.await()
     * // result == computeResult() return value (leader acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the action to run when leader acquisition succeeds. Returns the result directly.
     * @return [VirtualFuture] resolving to the [action] result, or `null` when leader acquisition fails
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?>

    /**
     * Runs [action] on a Virtual Thread if elected for [slot], stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when elected.
     * @return [VirtualFuture] resolving to the action result, or `null` when not elected.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for the Virtual Thread slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when elected.
     * @return [VirtualFuture] resolving to [LeaderRunResult.Elected] or [LeaderRunResult.Skipped].
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        val source: VirtualFuture<T?> = runAsyncIfLeader(slot.lockName) {
            elected.set(true)
            action()
        }
        val mapped: java.util.concurrent.CompletableFuture<LeaderRunResult<T>> =
            source.toCompletableFuture().handle { value, failure ->
                when {
                    failure != null && elected.get() -> failure.toActionFailedResult()
                    failure != null -> throw failure.asCompletionException()
                    elected.get() -> LeaderRunResult.Elected(value) as LeaderRunResult<T>
                    else -> LeaderRunResult.Skipped as LeaderRunResult<T>
                }
            }
        return VirtualFuture(mapped)
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

package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Defines the contract for Virtual Thread-based multi-leader asynchronous election.
 *
 * ## Difference from [VirtualThreadLeaderElector]
 * - [VirtualThreadLeaderElector] limits leaders to 1 per `lockName`.
 * - [VirtualThreadLeaderGroupElector] allows up to [maxLeaders] concurrent leaders.
 *
 * ## Difference from [LeaderGroupElector]
 * - [LeaderGroupElector.runAsyncIfLeader] returns [java.util.concurrent.CompletableFuture].
 * - This interface's [runAsyncIfLeader] returns [VirtualFuture] and `action` is a simpler `() -> T` lambda.
 * - Virtual Thread-based, so carrier threads are not consumed by I/O blocking operations.
 *
 * ## Behavior / Contract
 * - Implementations run at most [maxLeaders] concurrent `action` invocations per `lockName`.
 * - When all slots are occupied, the Virtual Thread blocks until a slot becomes available.
 * - Slots are always released even when `action` throws an exception.
 * - State query methods ([state], [activeCount], [availableSlots]) are inherited from [LeaderGroupElectionState].
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runAsyncIfLeader("batch-job") { processChunk() }.await()
 * ```
 */
interface VirtualThreadLeaderGroupElector: LeaderGroupElectionState {

    /**
     * Acquires a slot and, when elected as leader, executes [action] asynchronously on a Virtual Thread.
     *
     * ## Behavior / Contract
     * - When all slots are occupied, the Virtual Thread blocks until a slot becomes available.
     * - Slots are always released even when [action] throws an exception.
     * - Use [VirtualFuture.await] to wait synchronously or [VirtualFuture.toCompletableFuture] to convert.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job-lock") { computeResult() }.await()
     * // result == computeResult() return value (slot acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param action the action to run when elected as leader. Returns the result directly.
     * @return [VirtualFuture] resolving to the [action] result, or `null` when no slot is acquired
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?>

    /**
     * Runs [action] on a Virtual Thread if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when a slot is acquired.
     * @return [VirtualFuture] resolving to the action result, or `null` when no slot acquired.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for the Virtual Thread group slot election.
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

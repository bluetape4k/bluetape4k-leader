package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Defines the contract for semaphore-based multi-leader asynchronous election.
 *
 * ## Relationship with [LeaderGroupElector]
 * - [LeaderGroupElector] extends [AsyncLeaderGroupElector] and adds the synchronous [LeaderGroupElector.runIfLeader].
 * - [AsyncLeaderGroupElector] defines only asynchronous execution ([runAsyncIfLeader]).
 *
 * ## Difference from [AsyncLeaderElector]
 * - [AsyncLeaderElector] limits leaders to 1 per `lockName`.
 * - [AsyncLeaderGroupElector] allows up to [maxLeaders] concurrent leaders.
 *
 * ## Behavior / Contract
 * - Implementations run at most [maxLeaders] concurrent `action` invocations per `lockName`.
 * - When all slots are occupied, the [Executor] thread blocks until a slot becomes available.
 * - Slots are always released even when `action` throws an exception.
 * - State query methods ([state], [activeCount], [availableSlots]) are inherited from [LeaderGroupElectionState].
 *
 * ```kotlin
 * val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runAsyncIfLeader("batch-job") {
 *     CompletableFuture.completedFuture(processChunk())
 * }.join()
 * ```
 */
interface AsyncLeaderGroupElector: LeaderGroupElectionState {

    /**
     * Acquires a slot and, when elected as leader, executes the asynchronous [action].
     *
     * ## Behavior / Contract
     * - When all slots are occupied, the [executor] thread blocks until a slot becomes available.
     * - The slot is held until the [CompletableFuture] returned by [action] completes.
     * - The slot is always released even when [action] fails (exception or future failure).
     * - The default [executor] is the [VirtualThreadExecutor] singleton, suitable for blocking work.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job-lock") {
     *     CompletableFuture.completedFuture(42)
     * }.join()
     * // result == 42 (slot acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param executor the [Executor] for async execution. Defaults to the [VirtualThreadExecutor] singleton
     * @param action the async action to run when elected as leader
     * @return [CompletableFuture] resolving to the [action] result, or `null` when no slot is acquired
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * Runs [action] asynchronously if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to carry [slot.leaderId]
     * into the lease/lock audit identity.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when a slot is acquired.
     * @return [CompletableFuture] resolving to the action result, or `null` when no slot acquired.
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
     * Returns [LeaderRunResult] for the async group slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to [LeaderRunResult.Elected] or [LeaderRunResult.Skipped].
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

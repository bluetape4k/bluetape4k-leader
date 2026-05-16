package io.bluetape4k.leader.local

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Local (single-JVM) asynchronous leader election implementation using [ReentrantLock].
 *
 * ## Behavior
 * - Implements only [AsyncLeaderElector]; use when a synchronous [runIfLeader] is not needed.
 * - Executes [CompletableFuture]-based async actions serially for the same [lockName].
 * - The [Executor] thread that acquired the lock holds it until the [CompletableFuture] returned by [action] completes.
 * - The lock is always released even when an exception or future failure occurs during [action] execution.
 * - Suitable for serializing async execution within a single JVM process, not a distributed environment.
 *
 * ```kotlin
 * val election = LocalAsyncLeaderElector()
 * val result = election.runAsyncIfLeader("job-lock") {
 *     CompletableFuture.completedFuture("done")
 * }.join()
 * // result == "done"
 * ```
 */
class LocalAsyncLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), AsyncLeaderElector {

    /**
     * Acquires the [ReentrantLock] for [lockName] and executes [action] asynchronously on [executor].
     *
     * - Holds the lock until the [CompletableFuture] returned by [action] completes.
     * - Safely releases the lock even when [action] fails (exception or future failure).
     * - If another thread holds the lock for the same [lockName], the [executor] thread blocks.
     *
     * ```kotlin
     * val election = LocalAsyncLeaderElector()
     * val result = election.runAsyncIfLeader("job-lock") {
     *     CompletableFuture.completedFuture("done")
     * }.join()
     * // result == "done"
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param executor the [Executor] for async execution
     * @param action the async action to run when leader acquisition succeeds
     * @return [CompletableFuture] resolving to the [action] result, or `null` when leader acquisition fails
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { tryWithLeaderLock(lockName, options.waitTime) { action().join() } },
            executor
        )

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            {
                tryWithLeaderLock(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                    waitTime = options.waitTime,
                ) { action().join() }
            },
            executor
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        return CompletableFuture.supplyAsync(
            {
                tryWithLeaderLock(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                    waitTime = options.waitTime,
                ) {
                    elected.set(true)
                    action().join()
                }
            },
            executor
        ).handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
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

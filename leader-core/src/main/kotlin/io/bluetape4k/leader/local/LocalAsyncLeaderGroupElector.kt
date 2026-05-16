package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local (single-JVM) async-only multi-leader election implementation extending [AbstractLocalLeaderGroupElector].
 *
 * ## Behavior
 * - Supports only the async [runAsyncIfLeader]. Use [LocalLeaderGroupElector] when synchronous execution is needed.
 * - Slot management (Semaphore pool, state queries) is handled by [AbstractLocalLeaderGroupElector].
 * - The default [Executor] is the [VirtualThreadExecutor] singleton.
 * - Suitable for limiting concurrent execution within a single JVM process, not a distributed environment.
 *
 * ```kotlin
 * val election = LocalAsyncLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // Up to 3 Virtual Threads execute concurrently
 * val result = election.runAsyncIfLeader("batch-job") {
 *     CompletableFuture.completedFuture(processChunk())
 * }.join()
 * ```
 *
 * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
 */
class LocalAsyncLeaderGroupElector private constructor(
    options: LeaderGroupElectionOptions,
): AbstractLocalLeaderGroupElector(options), AsyncLeaderGroupElector {

    companion object: KLogging() {
        /**
         * Creates a [LocalAsyncLeaderGroupElector] instance using [LeaderGroupElectionOptions].
         *
         * ```kotlin
         * val election = LocalAsyncLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runAsyncIfLeader("batch-job") {
         *     CompletableFuture.completedFuture("done")
         * }.join()
         * // result == "done"
         * ```
         *
         * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
         * @return an [AsyncLeaderGroupElector] implementation instance
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): AsyncLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalAsyncLeaderGroupElector)
    }

    /**
     * Acquires a slot for [lockName] on [executor] and executes the async [action].
     *
     * ```kotlin
     * val election = LocalAsyncLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runAsyncIfLeader("batch-job") {
     *     CompletableFuture.completedFuture(42)
     * }.join()
     * // result == 42
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param executor the [Executor] for async execution
     * @param action the async action to run when a slot is acquired
     * @return [CompletableFuture] resolving to the [action] result, or `null` when no slot is acquired
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { tryWithPermit(lockName) { action().join() } },
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
                tryWithPermit(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
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
                tryWithPermit(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
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

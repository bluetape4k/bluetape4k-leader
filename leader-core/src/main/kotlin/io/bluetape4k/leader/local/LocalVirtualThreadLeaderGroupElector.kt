package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local (single-JVM) multi-leader Virtual Thread async election implementation extending [AbstractLocalLeaderGroupElector].
 *
 * ## Behavior
 * - Supports [VirtualFuture]-based [runAsyncIfLeader].
 * - Slot management (Semaphore pool, state queries) is handled by [AbstractLocalLeaderGroupElector].
 * - Virtual Threads release the carrier thread when blocking on [java.util.concurrent.Semaphore.acquire].
 * - Suitable for limiting concurrent execution within a single JVM process, not a distributed environment.
 *
 * ## Difference from [LocalAsyncLeaderGroupElector]
 * - `action` is a simpler `() -> T`; [java.util.concurrent.CompletableFuture] wrapping is not needed.
 * - The return type is [VirtualFuture], making the `await()` API explicit.
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // Up to 3 Virtual Threads execute concurrently
 * val result = election.runAsyncIfLeader("batch-job") { processChunk() }.await()
 * ```
 *
 * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
 */
class LocalVirtualThreadLeaderGroupElector private constructor(
    options: LeaderGroupElectionOptions,
): AbstractLocalLeaderGroupElector(options), VirtualThreadLeaderGroupElector {

    companion object: KLogging() {

        /**
         * Creates a [LocalVirtualThreadLeaderGroupElector] instance using [LeaderGroupElectionOptions].
         *
         * ```kotlin
         * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runAsyncIfLeader("batch-job") { "done" }.await()
         * // result == "done"
         * ```
         *
         * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
         * @return a [VirtualThreadLeaderGroupElector] implementation instance
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): VirtualThreadLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalVirtualThreadLeaderGroupElector)
    }

    /**
     * Acquires a slot for [lockName] on a Virtual Thread and executes [action].
     *
     * ```kotlin
     * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runAsyncIfLeader("batch-job") { 42 }.await()
     * // result == 42
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param action the action to run when a slot is acquired
     * @return [VirtualFuture] resolving to the [action] result, or `null` when no slot is acquired
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithPermit(lockName, action)
        }

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                action = action,
            )
        }

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        val source: VirtualFuture<T?> = virtualFuture {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
            ) {
                elected.set(true)
                action()
            }
        }
        val mapped: java.util.concurrent.CompletableFuture<LeaderRunResult<T>> =
            source.toCompletableFuture().handle { value, failure ->
                when {
                    failure != null && elected.get() -> failure.toActionFailedResult()
                    failure != null -> throw failure.asCompletionException()
                    elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId) as LeaderRunResult<T>
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

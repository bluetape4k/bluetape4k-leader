package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Local (single-JVM) leader election implementation using [ReentrantLock] and Virtual Threads.
 *
 * ## Behavior
 * - Implements [VirtualThreadLeaderElector]; `action` is a `() -> T` lambda that returns the result directly.
 * - Executes asynchronously via [VirtualFuture]; Virtual Threads release the carrier thread while waiting for the lock.
 * - Uses [ReentrantLock] to guarantee serial execution for the same `lockName`.
 * - Due to [ReentrantLock] semantics, nested calls (re-entrancy) with the same `lockName` from the same thread are allowed.
 * - Suitable for serializing async execution within a single JVM process, not a distributed environment.
 *
 * ## Difference from [LocalAsyncLeaderElector]
 * - `action` is a simpler `() -> T`; [java.util.concurrent.CompletableFuture] wrapping is not needed.
 * - The return type is [VirtualFuture], making the `await()` API explicit.
 * - Virtual Thread-based, so carrier threads are not consumed by I/O blocking operations.
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderElector()
 * val result = election.runAsyncIfLeader("job-lock") { "done" }.await()
 * // result == "done"
 * ```
 */
class LocalVirtualThreadLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), VirtualThreadLeaderElector {

    /**
     * Acquires the [ReentrantLock] for [lockName] on a Virtual Thread and executes [action].
     *
     * - Virtual Threads release the carrier thread when blocking on [ReentrantLock.lock].
     * - The lock is safely released even when [action] throws an exception.
     * - Nested calls (re-entrancy) with the same `lockName` from the same thread are allowed.
     *
     * ```kotlin
     * val election = LocalVirtualThreadLeaderElector()
     * val result = election.runAsyncIfLeader("job-lock") { "done" }.await()
     * // result == "done"
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the action to run when leader acquisition succeeds
     * @return [VirtualFuture] resolving to the [action] result, or `null` when leader acquisition fails
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithLeaderLock(lockName, options.waitTime, action)
        }

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
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
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
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

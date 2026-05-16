package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock

/**
 * Local (single-JVM) leader election implementation using [ReentrantLock].
 *
 * ## Behavior
 * - Guarantees serial execution via mutual exclusion between threads for the same `lockName`.
 * - The thread that acquires the lock runs `action` as leader; other threads block until the lock is released.
 * - Due to [ReentrantLock] semantics, nested calls (re-entrancy) with the same `lockName` from the same thread are allowed.
 * - Suitable for serializing concurrent execution within a single JVM process, not a distributed environment.
 *
 * ```kotlin
 * val election = LocalLeaderElector()
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 */
class LocalLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), LeaderElector {

    /**
     * Acquires the [ReentrantLock] for [lockName] and executes [action] serially.
     *
     * If another thread holds the lock for the same [lockName], this thread blocks until it is released.
     * Re-entrant calls from the same thread acquire the lock immediately.
     *
     * ```kotlin
     * val election = LocalLeaderElector()
     * val result = election.runIfLeader("job-lock") { 42 }
     * // result == 42
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the synchronous action to run when leader acquisition succeeds
     * @return the [action] result, or `null` when leader acquisition fails
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        tryWithLeaderLock(lockName, options.waitTime, action)

    /**
     * Acquires the [ReentrantLock] for [lockName] and executes [action] asynchronously on [executor].
     *
     * Holds the lock until the [CompletableFuture] returned by [action] completes.
     * If another thread holds the lock for the same [lockName], the [executor] thread blocks.
     *
     * ```kotlin
     * val election = LocalLeaderElector()
     * val result = election.runAsyncIfLeader("job-lock") {
     *     CompletableFuture.completedFuture("async-ok")
     * }.join()
     * // result == "async-ok"
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
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithLeaderLock(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            waitTime = options.waitTime,
            action = action,
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
            ) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }
}

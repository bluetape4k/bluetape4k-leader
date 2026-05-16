package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Local (single-JVM) multi-leader election implementation extending [AbstractLocalLeaderGroupElector].
 *
 * ## Behavior
 * - Supports both synchronous [runIfLeader] and asynchronous [runAsyncIfLeader].
 * - Slot management (Semaphore pool, state queries) is handled by [AbstractLocalLeaderGroupElector].
 * - Suitable for limiting concurrent execution within a single JVM process, not a distributed environment.
 *
 * ```kotlin
 * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // Synchronous execution (up to 3 threads concurrently)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 *
 * // Asynchronous execution
 * val future = election.runAsyncIfLeader("batch-job") { CompletableFuture.completedFuture(42) }
 * ```
 *
 * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
 */
class LocalLeaderGroupElector private constructor(options: LeaderGroupElectionOptions):
    AbstractLocalLeaderGroupElector(options), LeaderGroupElector {

    companion object: KLogging() {

        /**
         * Creates a [LocalLeaderGroupElector] instance using [LeaderGroupElectionOptions].
         *
         * ```kotlin
         * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runIfLeader("batch-job") { "done" }
         * // result == "done"
         * ```
         *
         * @param options leader group election options. Defaults to [LeaderGroupElectionOptions.Default]
         * @return a [LeaderGroupElector] implementation instance
         */
        operator fun invoke(options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default): LeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalLeaderGroupElector)
    }

    /**
     * Acquires a slot for [lockName] and executes [action] synchronously.
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runIfLeader("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param action the synchronous action to run when a slot is acquired
     * @return the [action] result, or `null` when no slot is acquired
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        return tryWithPermit(lockName, action)
    }

    /**
     * Acquires a slot for [lockName] on [executor] and executes the async [action].
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runAsyncIfLeader("batch-job") {
     *     CompletableFuture.completedFuture(42)
     * }.join()
     * // result == 42
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor]
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
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
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

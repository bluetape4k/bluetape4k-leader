package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException

/**
 * Defines the contract for semaphore-based multi-leader election.
 *
 * ## Difference from [LeaderElector]
 * - [LeaderElector] limits leaders to 1 per `lockName`.
 * - [LeaderGroupElector] allows up to [maxLeaders] concurrent leaders.
 * - Internally uses `Semaphore(maxLeaders)` to limit the number of concurrent executions.
 *
 * ## Relationship with [AsyncLeaderGroupElector]
 * - Extends [AsyncLeaderGroupElector] to add the synchronous [runIfLeader].
 * - Async execution ([AsyncLeaderGroupElector.runAsyncIfLeader]) and state query methods are inherited from the parent interface.
 *
 * ## Behavior / Contract
 * - Implementations run at most [maxLeaders] concurrent `action` invocations per `lockName`.
 * - When all slots are occupied, the calling thread blocks until a slot becomes available.
 * - Slots are always released even when `action` throws an exception.
 * - State query methods ([state], [activeCount], [availableSlots]) may return approximate values.
 *
 * ```kotlin
 * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunk() }
 *
 * println(election.state("batch-job"))  // LeaderGroupState(activeCount=2, ...)
 * ```
 */
interface LeaderGroupElector: AsyncLeaderGroupElector {

    /**
     * Acquires a slot and, when elected as leader, executes [action].
     *
     * ## Behavior / Contract
     * - When all slots are occupied, the calling thread blocks until a slot becomes available.
     * - Slots are always released even when [action] throws an exception.
     * - [activeCount] increments while [action] is running and decrements when it completes.
     *
     * ```kotlin
     * val result = election.runIfLeader("job-lock") { compute() }
     * // result == compute() return value (slot acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for group leader election
     * @param action the synchronous action to run when elected as leader
     * @return the [action] result, or `null` when no slot is acquired
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * Returns the group leader election result as a [LeaderRunResult].
     *
     * Same method name as [LeaderElector.runIfLeaderResult] — the "Group" context is conveyed by the interface type.
     * Resolves the `null`-return ambiguity of `runIfLeader`:
     * even when action() returns null, it is distinguished as [LeaderRunResult.Elected],
     * while a missed slot is distinguished as [LeaderRunResult.Skipped].
     *
     * An equivalent result API is provided on coroutine/async/virtual-thread group electors as well.
     * `CancellationException` is propagated to the caller without wrapping in [LeaderRunResult.ActionFailed].
     * `InterruptedException` is re-propagated after restoring the interrupt flag.
     *
     * @param lockName the lock name used for group leader election
     * @param action the synchronous action to run when a slot is acquired
     * @return [LeaderRunResult.Elected] (action ran), [LeaderRunResult.Skipped] (no slot acquired),
     *   or [LeaderRunResult.ActionFailed] (action failed)
     */
    fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(lockName) {
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
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }

    /**
     * Runs [action] if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog] on first use per `(implClass, slot)` pair.
     * Backend implementations MUST override this method to stamp [slot.leaderId] into
     * [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when a slot is acquired.
     * @return [action] result, or `null` when no slot acquired.
     */
    fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this group slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when a slot is acquired.
     * @return [LeaderRunResult.Elected] (action ran), [LeaderRunResult.Skipped] (no slot acquired),
     *   or [LeaderRunResult.ActionFailed] (action failed).
     */
    fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        var elected = false
        val value = try {
            runIfLeader(slot.lockName) {
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
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }
}

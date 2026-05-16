package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException

/**
 * Defines the contract for synchronous leader election execution.
 *
 * ## Behavior / Contract
 * - Only the call that successfully acquires leadership for the given [lockName] executes [action].
 * - Leader acquisition/release strategy and failure handling (exceptions/retries) follow the implementation policy.
 * - The thread and context at the time [action] runs may vary by implementation.
 *
 * ```kotlin
 * val leaderElection = DefaultLeaderElector()
 * val result = leaderElection.runIfLeader("daily-job") {
 *     "done"
 * }
 * // result == "done" (leader acquired path)
 */
interface LeaderElector: AsyncLeaderElector {

    /**
     * Executes the synchronous [action] only when elected as leader.
     *
     * ## Behavior / Contract
     * - [action] is executed exactly once when leadership for [lockName] is successfully acquired.
     * - Exceptions thrown from [action] are propagated to the caller.
     * - [lockName] validation rules (e.g. blank allowed) follow the implementation policy.
     * - Returns `null` if leadership is not acquired within [waitTime] (ShedLock skip-on-contention behavior).
     *
     * ```kotlin
     * val value = leaderElection.runIfLeader("job-lock") { 42 }
     * // value == 42 (leader acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the synchronous action to run when elected
     * @return the [action] result, or `null` if leadership was not acquired
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * Returns the leader election result as a [LeaderRunResult].
     *
     * Resolves the ambiguity of `runIfLeader` returning `null`:
     * even if action() returns null, the result is [LeaderRunResult.Elected];
     * a lock-not-acquired outcome is represented as [LeaderRunResult.Skipped].
     *
     * An equivalent result API is provided for coroutine/async/virtual-thread electors.
     * `CancellationException` is propagated directly to the caller without wrapping in [LeaderRunResult.ActionFailed].
     * `InterruptedException` restores the interrupt flag before rethrowing.
     *
     * ```kotlin
     * when (val r = election.runIfLeaderResult("job-lock") { compute() }) {
     *     is LeaderRunResult.Elected -> println("elected, value=${r.value}")
     *     is LeaderRunResult.Skipped -> println("skipped — lock not acquired")
     *     is LeaderRunResult.ActionFailed -> println("action failed: ${r.cause.message}")
     * }
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the synchronous action to run when elected
     * @return [LeaderRunResult.Elected] (action ran), [LeaderRunResult.Skipped] (lock not acquired),
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
     * Runs [action] if elected for [slot], stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog] on first use per `(implClass, slot)` pair.
     * Backend implementations MUST override this method to stamp [slot.leaderId] into
     * [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when elected.
     * @return [action] result, or `null` when not elected.
     */
    fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked because the bridge cannot verify that the backend actually used this slot's identity.
     *
     * Backend implementations MUST override BOTH slot variants
     * ([runIfLeader] and [runIfLeaderResult]) to carry [slot.leaderId] through to
     * [LeaderRunResult.Elected.leaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when elected.
     * @return [LeaderRunResult.Elected] (action ran), [LeaderRunResult.Skipped] (not elected),
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

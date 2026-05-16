package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Defines the contract for coroutine-based leader election execution.
 *
 * ## Behavior / Contract
 * - Implementations execute [action] only for the call that successfully acquires leadership for [lockName].
 * - [action] is a suspend function; the call context and dispatcher follow the implementation policy.
 * - Returns `null` when leadership is not acquired (ShedLock skip style).
 * - If the coroutine is cancelled while [action] is running, the lock/slot must be released,
 *   and `CancellationException` must be re-propagated to the caller after the release.
 *
 * ```kotlin
 * val result = election.runIfLeader("sync-job") { "ok" }
 * // result == "ok"
 * ```
 */
interface SuspendLeaderElector: LeaderElectionState {

    /**
     * Executes the suspend [action] when leadership is successfully acquired.
     *
     * ## Behavior / Contract
     * - [action] is executed exactly once when leadership for [lockName] is acquired.
     * - Exceptions from [action] are propagated to the caller.
     * - [lockName] validation rules follow the implementation policy.
     *
     * ```kotlin
     * val value = election.runIfLeader("job-lock") { 7 }
     * // value == 7 (leader acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for leader election
     * @param action the suspend action to run when elected
     * @return [action] result, or `null` if leadership was not acquired
     */
    suspend fun <T> runIfLeader(
        lockName: String,
        action: suspend () -> T,
    ): T?

    /**
     * Result-type API that explicitly represents the leader election outcome.
     *
     * Removes the ambiguity when [runIfLeader] returns `null`: (a) not elected vs (b) action returned null.
     *
     * ## Behavior / Contract
     * - Leadership acquired → [LeaderRunResult.Elected]`(value)` — `value` is the action return value (may be null)
     * - Not elected → [LeaderRunResult.Skipped]
     * - `elected: Boolean` flag pattern for precise classification (even if action returns null, result is [LeaderRunResult.Elected])
     *
     * ## binary-compat (Step 2-R R3-F3)
     * Added as a Kotlin interface default fun — compiled as a JVM `default` method under `-jvm-default=enable`,
     * preserving binary compatibility with existing external implementations.
     *
     * ```kotlin
     * val result = election.runIfLeaderResultSuspend("job-lock") { computeResult() }
     * when (result) {
     *     is LeaderRunResult.Elected -> println("elected, value=${result.value}")
     *     LeaderRunResult.Skipped   -> println("not elected")
     *     is LeaderRunResult.ActionFailed -> println("action failed: ${result.cause.message}")
     * }
     * ```
     *
     * `CancellationException` is rethrown directly and is not wrapped as [LeaderRunResult.ActionFailed].
     *
     * @param lockName the lock name used for leader election
     * @param action the suspend action to run when elected
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (not elected)
     */
    suspend fun <T> runIfLeaderResultSuspend(
        lockName: String,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
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
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when elected.
     * @return [action] result, or `null` when not elected.
     */
    suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this suspend slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * Cancellation: the underlying [runIfLeader] propagates `CancellationException` directly;
     * no `runCatching` is used around suspend calls.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when elected.
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (not elected).
     */
    suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        var elected = false
        val value = try {
            runIfLeader(slot.lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
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

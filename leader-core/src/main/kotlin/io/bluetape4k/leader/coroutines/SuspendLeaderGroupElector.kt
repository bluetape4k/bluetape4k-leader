package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Defines the contract for coroutine-based multi-leader election.
 *
 * ## Difference from [SuspendLeaderElector]
 * - [SuspendLeaderElector] limits leaders to 1 per `lockName`.
 * - [SuspendLeaderGroupElector] allows up to [maxLeaders] concurrent leaders.
 * - Internally uses `kotlinx.coroutines.sync.Semaphore(maxLeaders)`.
 *
 * ## [LeaderGroupElectionState] inheritance
 * - Shares state query methods: [maxLeaders], [activeCount], [availableSlots], [state].
 *
 * ## Behavior / Contract
 * - Implementations run up to [maxLeaders] concurrent `action` invocations per `lockName`.
 * - If all slots are full and a slot cannot be acquired within [waitTime], returns `null` (ShedLock skip behavior).
 * - The slot is always released even if `action` throws.
 * - On coroutine cancellation, the slot must be released and `CancellationException` must be rethrown after release.
 * - State query methods ([state], [activeCount], [availableSlots]) may return approximate values.
 *
 * ```kotlin
 * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 *
 * println(election.state("batch-job"))  // LeaderGroupState(activeCount=2, ...)
 * ```
 */
interface SuspendLeaderGroupElector: LeaderGroupElectionState {

    /**
     * Acquires a slot and runs suspend [action] when elected as leader.
     *
     * ## Behavior / Contract
     * - If all slots are full, the coroutine suspends until a slot becomes available.
     * - The slot is always released even if [action] throws.
     * - [activeCount] increases while [action] runs and decreases on completion.
     *
     * ```kotlin
     * val result = election.runIfLeader("job-lock") { computeSuspend() }
     * // result == computeSuspend() return value (slot acquired) or null (not acquired)
     * ```
     *
     * @param lockName the lock name used for leader group election
     * @param action the suspend action to run when elected as leader
     * @return [action] result, or `null` if the slot was not acquired
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?

    /**
     * Result-typed API that makes the leader election outcome explicit.
     *
     * Removes ambiguity when [runIfLeader] returns `null`: (a) slot not acquired vs (b) action returned null.
     *
     * ## Behavior / Contract
     * - Slot acquired → [LeaderRunResult.Elected]`(value)` — `value` is the action return value (may be null)
     * - Slot not acquired → [LeaderRunResult.Skipped]
     * - Action failed → [LeaderRunResult.ActionFailed]
     * - `CancellationException` is rethrown directly, not wrapped in [LeaderRunResult.ActionFailed]
     * - Accurate classification via `elected: Boolean` flag (returns [LeaderRunResult.Elected] even if action returns null)
     *
     * ## binary-compat (Step 2-R R3-F3)
     * Added as Kotlin interface default fun — compiled as JVM `default` method under `-jvm-default=enable`,
     * preserving binary compatibility for existing external implementations.
     *
     * ```kotlin
     * val result = election.runIfLeaderResultSuspend("batch-job") { processChunkSuspend() }
     * when (result) {
     *     is LeaderRunResult.Elected -> println("elected, value=${result.value}")
     *     LeaderRunResult.Skipped   -> println("slot full — skipped")
     *     is LeaderRunResult.ActionFailed -> println("action failed: ${result.cause.message}")
     * }
     * ```
     *
     * @param lockName the lock name used for leader group election
     * @param action the suspend action to run when a slot is acquired
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (slot not acquired)
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
     * Runs [action] if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when a slot is acquired.
     * @return [action] result, or `null` when no slot acquired.
     */
    suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this suspend group slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * Cancellation: the underlying [runIfLeader] propagates `CancellationException` directly;
     * no `runCatching` is used around suspend calls.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when a slot is acquired.
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (no slot acquired).
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

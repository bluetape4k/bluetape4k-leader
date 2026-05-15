package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.leader.LeaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maps this publisher's event stream into a single-leader [StateFlow] for [lockName].
 *
 * ## Behavior / Contract
 * - Initial value is [LeaderState.empty] for [lockName].
 * - [LeaderElectionEvent.Elected] transitions to [LeaderState.occupied]:
 *   `auditLeaderId` is taken from [LeaderElectionEvent.Elected.leaderId] when available,
 *   falling back to [LeaderNodeId.Default]; `leaseUntil` is taken from
 *   [LeaderElectionEvent.Elected.leaseExpiry].
 * - [LeaderElectionEvent.Revoked] transitions back to [LeaderState.empty].
 * - [LeaderElectionEvent.Skipped] produces no state transition.
 * - This is a single-leader projection. For group electors with `maxLeaders > 1`, use
 *   [leaderGroupStateFlow] so one revoked group slot does not clear the whole lock state.
 * - Events for other lock names are filtered out, so a single publisher serving multiple locks
 *   can be safely observed per-lock.
 * - The returned [StateFlow] is hot and shares the upstream according to [started].
 * - With the default [SharingStarted.Eagerly], the upstream collector starts undispatched before
 *   this function returns, so hot publishers with no replay do not drop events emitted immediately
 *   after `leaderStateFlow()` returns.
 *
 * ```kotlin
 * val stateFlow = elector.leaderStateFlow("my-lock", scope)
 * stateFlow.collect { state ->
 *     if (state.isOccupied) println("Leader: ${state.leader?.auditLeaderId}")
 * }
 * ```
 *
 * @param lockName the lock to observe.
 * @param scope the [CoroutineScope] used to share the upstream flow.
 * @param started sharing strategy; defaults to [SharingStarted.Eagerly].
 */
fun LeaderElectionEventPublisher.leaderStateFlow(
    lockName: String,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
): StateFlow<LeaderState> =
    events.toLeaderStates(lockName)
        .toStateFlow(LeaderState.empty(lockName), scope, started)

/**
 * Maps this publisher's event stream into a group-leader [StateFlow] for [lockName].
 *
 * ## Behavior / Contract
 * - Initial value is an empty [LeaderGroupState] for [lockName] and [maxLeaders].
 * - [LeaderElectionEvent.Elected] increments [LeaderGroupState.activeCount] up to [maxLeaders].
 * - [LeaderElectionEvent.Revoked] decrements [LeaderGroupState.activeCount] down to zero.
 * - [LeaderElectionEvent.Skipped] produces no state transition.
 * - The `leaders` list is intentionally empty because [LeaderElectionEvent.Revoked] does not
 *   carry slot or leader identity, so this projection can report count semantics but cannot
 *   prove which leader remains after a partial revoke.
 * - Events for other lock names are filtered out, so a single publisher serving multiple locks
 *   can be safely observed per-lock.
 * - With the default [SharingStarted.Eagerly], the upstream collector starts undispatched before
 *   this function returns, matching [leaderStateFlow] for hot publishers with no replay.
 *
 * ```kotlin
 * val stateFlow = elector.leaderGroupStateFlow("batch-job", maxLeaders = 3, scope)
 * stateFlow.collect { state ->
 *     println("Active leaders: ${state.activeCount}/${state.maxLeaders}")
 * }
 * ```
 *
 * @param lockName the lock to observe.
 * @param maxLeaders maximum number of leaders the group elector allows.
 * @param scope the [CoroutineScope] used to share the upstream flow.
 * @param started sharing strategy; defaults to [SharingStarted.Eagerly].
 */
fun LeaderElectionEventPublisher.leaderGroupStateFlow(
    lockName: String,
    maxLeaders: Int,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
): StateFlow<LeaderGroupState> {
    val initialState = LeaderGroupState(lockName, maxLeaders, activeCount = 0)
    return events.toLeaderGroupStates(lockName, maxLeaders)
        .toStateFlow(initialState, scope, started)
}

private fun Flow<LeaderElectionEvent>.toLeaderStates(lockName: String): Flow<LeaderState> =
    filter { it.lockName == lockName }
        .filterNot { it is LeaderElectionEvent.Skipped }
        .map { it.toLeaderState(lockName) }

private fun Flow<LeaderElectionEvent>.toLeaderGroupStates(
    lockName: String,
    maxLeaders: Int,
): Flow<LeaderGroupState> =
    filter { it.lockName == lockName }
        .filterNot { it is LeaderElectionEvent.Skipped }
        .runningFold(0) { activeCount, event ->
            when (event) {
                is LeaderElectionEvent.Elected -> (activeCount + 1).coerceAtMost(maxLeaders)
                is LeaderElectionEvent.Revoked -> (activeCount - 1).coerceAtLeast(0)
                is LeaderElectionEvent.Skipped -> activeCount
            }
        }
        .map { activeCount ->
            LeaderGroupState(lockName, maxLeaders, activeCount)
        }

private fun LeaderElectionEvent.toLeaderState(lockName: String): LeaderState =
    when (this) {
        is LeaderElectionEvent.Elected -> LeaderState.occupied(
            lockName,
            LeaderLease(
                auditLeaderId = leaderId ?: LeaderNodeId.Default,
                leaseUntil = leaseExpiry,
            )
        )

        is LeaderElectionEvent.Revoked -> LeaderState.empty(lockName)
        is LeaderElectionEvent.Skipped -> LeaderState.empty(lockName)
    }

private fun <T> Flow<T>.toStateFlow(
    initialValue: T,
    scope: CoroutineScope,
    started: SharingStarted,
): StateFlow<T> =
    if (started == SharingStarted.Eagerly) {
        // Identity check is intentional: SharingStarted.Eagerly is a singleton. Custom eager-like strategies
        // use stateIn(), so only the standard eager path gets the no-drop startup guarantee for hot publishers.
        val state = MutableStateFlow(initialValue)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collect { state.value = it }
        }
        state.asStateFlow()
    } else {
        stateIn(
            scope = scope,
            started = started,
            initialValue = initialValue,
        )
    }

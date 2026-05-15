package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LeaderElectionEventPublisher
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maps this publisher's event stream into a [StateFlow] that tracks the current [LeaderState]
 * for [lockName].
 *
 * ## Behavior / Contract
 * - Initial value is [LeaderState.empty] for [lockName].
 * - [LeaderElectionEvent.Elected] transitions to [LeaderState.occupied]:
 *   `auditLeaderId` is taken from [LeaderElectionEvent.Elected.leaderId] when available,
 *   falling back to [LeaderNodeId.Default]; `leaseUntil` is taken from
 *   [LeaderElectionEvent.Elected.leaseExpiry].
 * - [LeaderElectionEvent.Revoked] transitions back to [LeaderState.empty].
 * - [LeaderElectionEvent.Skipped] produces no state transition.
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
        .toStateFlow(lockName, scope, started)

private fun Flow<LeaderElectionEvent>.toLeaderStates(lockName: String): Flow<LeaderState> =
    filter { it.lockName == lockName }
        .filterNot { it is LeaderElectionEvent.Skipped }
        .map { it.toLeaderState(lockName) }

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

private fun Flow<LeaderState>.toStateFlow(
    lockName: String,
    scope: CoroutineScope,
    started: SharingStarted,
): StateFlow<LeaderState> =
    if (started == SharingStarted.Eagerly) {
        val state = MutableStateFlow(LeaderState.empty(lockName))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collect { state.value = it }
        }
        state.asStateFlow()
    } else {
        stateIn(
            scope = scope,
            started = started,
            initialValue = LeaderState.empty(lockName),
        )
    }

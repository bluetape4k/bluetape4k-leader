package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.support.requireGe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 지정한 lock의 lifecycle event를 단일 leader 상태 `StateFlow`로 접습니다.
 * @param lockName 상태를 집계할 event의 lock 이름입니다.
 * @param scope 파생 `StateFlow` collector coroutine의 수명주기를 소유하는 scope입니다.
 * @param started upstream collection을 시작·중지할 `SharingStarted` 정책입니다. 기본값은 `Eagerly`입니다.
 * @return 마지막 `Elected` 또는 `Revoked` event로 갱신되는 현재 lock 상태입니다.
 */
fun LeaderElectionEventPublisher.leaderStateFlow(
    lockName: String,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
): StateFlow<LeaderState> =
    events.toLeaderStates(lockName)
        .toStateFlow(LeaderState.empty(lockName), scope, started)

/**
 * 지정한 lock의 lifecycle event를 group leader 상태 `StateFlow`로 접습니다.
 * @param lockName 상태를 집계할 event의 lock 이름입니다.
 * @param maxLeaders 동시에 점유할 수 있는 slot 수입니다. `1` 이상이어야 합니다.
 * @param scope 파생 `StateFlow` collector coroutine의 수명주기를 소유하는 scope입니다.
 * @param started upstream collection을 시작·중지할 `SharingStarted` 정책입니다. 기본값은 `Eagerly`입니다.
 * @return 획득·해제 event 수에 따라 갱신되는 group slot 상태입니다.
 */
fun LeaderElectionEventPublisher.leaderGroupStateFlow(
    lockName: String,
    maxLeaders: Int,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
): StateFlow<LeaderGroupState> {
    maxLeaders.requireGe(1, "maxLeaders")
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
            leader ?: LeaderLease(
                auditLeaderId = leaderId ?: LeaderNodeId.Default,
                leaseUntil = leaseExpiry,
            ),
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
        // identity 비교는 의도적입니다. SharingStarted.Eagerly는 singleton이고, custom eager-like 전략은 stateIn()을 사용합니다.
        // 따라서 표준 eager 경로에만 hot publisher 시작 시 no-drop 보장을 제공합니다.
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

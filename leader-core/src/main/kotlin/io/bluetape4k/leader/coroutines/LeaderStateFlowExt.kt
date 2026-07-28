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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * `LeaderElectionEventPublisher` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
 * @param started `started` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderElectionEventPublisher.leaderStateFlow(
    lockName: String,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
): StateFlow<LeaderState> =
    events.toLeaderStates(lockName)
        .toStateFlow(LeaderState.empty(lockName), scope, started)

/**
 * `LeaderElectionEventPublisher` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @param maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
 * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
 * @param started `started` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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

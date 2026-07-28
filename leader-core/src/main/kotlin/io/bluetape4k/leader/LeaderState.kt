package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderState`는 single leader lock의 현재 상태 snapshot입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property status history record의 현재 또는 최종 상태입니다.
 * @property leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
 */
data class LeaderState(
    val lockName: String,
    val status: LeaderStatus,
    val leader: LeaderLease? = null,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * `empty` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun empty(lockName: String): LeaderState =
            LeaderState(lockName, LeaderStatus.Empty)

        /**
         * `occupied` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @param leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun occupied(lockName: String, leader: LeaderLease): LeaderState =
            LeaderState(lockName, LeaderStatus.Occupied, leader)
    }

    init {
        lockName.requireNotBlank("lockName")
        when (status) {
            LeaderStatus.Empty -> require(leader == null) { "leader must be null when status is Empty" }
            LeaderStatus.Occupied -> require(leader != null) { "leader must not be null when status is Occupied" }
        }
    }

    /**
     * `isEmpty` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val isEmpty: Boolean get() = status == LeaderStatus.Empty

    /**
     * `isOccupied` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val isOccupied: Boolean get() = status == LeaderStatus.Occupied
}

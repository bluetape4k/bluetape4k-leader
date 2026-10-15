package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireNull
import java.io.Serializable

/**
 * `LeaderState`는 single leader lock의 현재 상태 snapshot입니다.
 *
 * @property lockName 상태를 조회한 lock 이름입니다. 공백 문자열은 허용되지 않습니다.
 * @property status 단일 lock의 현재 상태로, `Empty`이면 `leader`가 `null`이고 `Occupied`이면 값이 있어야 합니다.
 * @property leader 현재 lock을 점유한 lease입니다. lock이 비었으면 `null`, 점유 중이면 반드시 설정되어야 합니다.
 */
data class LeaderState(
    val lockName: String,
    val status: LeaderStatus,
    val leader: LeaderLease? = null,
): Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * 지정한 lock의 빈 상태 snapshot을 생성합니다.
         * @param lockName 상태를 조회한 lock 이름입니다. 공백 문자열이면 거부됩니다.
         */
        fun empty(lockName: String): LeaderState =
            LeaderState(lockName, LeaderStatus.Empty)

        /**
         * 지정한 lease가 점유한 lock 상태 snapshot을 생성합니다.
         * @param lockName 상태를 조회한 lock 이름입니다. 공백 문자열이면 거부됩니다.
         * @param leader 해당 lock을 점유한 lease입니다.
         */
        fun occupied(lockName: String, leader: LeaderLease): LeaderState =
            LeaderState(lockName, LeaderStatus.Occupied, leader)
    }

    init {
        lockName.requireNotBlank("lockName")
        when (status) {
            LeaderStatus.Empty -> leader.requireNull { "leader must be null when status is Empty" }
            LeaderStatus.Occupied -> leader.requireNotNull { "leader must not be null when status is Occupied" }
        }
    }

    /**
     * 상태가 `Empty`인지 여부입니다.
     */
    val isEmpty: Boolean get() = status == LeaderStatus.Empty

    /**
     * 상태가 `Occupied`인지 여부입니다.
     */
    val isOccupied: Boolean get() = status == LeaderStatus.Occupied
}

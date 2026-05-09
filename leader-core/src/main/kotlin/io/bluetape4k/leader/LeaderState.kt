package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 단일 리더 선출의 현재 상태 스냅샷입니다.
 *
 * ## 계약
 * - 이 값은 조회 시점의 best-effort 스냅샷입니다.
 * - lock 획득 가능 여부 판단에는 [state] 대신 각 elector의 원자적 acquire 경로를 사용해야 합니다.
 * - [status]가 [LeaderStatus.Empty]이면 [leader]는 `null`입니다.
 * - [status]가 [LeaderStatus.Occupied]이면 [leader]는 `null`이 아닙니다.
 *
 * ```kotlin
 * val state = election.state("batch-lock")
 * if (state.isOccupied) {
 *     println("leader=${state.leader?.leaderId}")
 * }
 * ```
 */
data class LeaderState(
    val lockName: String,
    val status: LeaderStatus,
    val leader: LeaderLease? = null,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * 리더가 없는 상태 스냅샷을 생성합니다.
         */
        fun empty(lockName: String): LeaderState =
            LeaderState(lockName, LeaderStatus.Empty)

        /**
         * 리더가 점유 중인 상태 스냅샷을 생성합니다.
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

    /** 현재 리더가 없는지 여부입니다. */
    val isEmpty: Boolean get() = status == LeaderStatus.Empty

    /** 현재 리더가 선출되어 있는지 여부입니다. */
    val isOccupied: Boolean get() = status == LeaderStatus.Occupied
}

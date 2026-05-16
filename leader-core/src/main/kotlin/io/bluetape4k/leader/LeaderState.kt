package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Current state snapshot for a single leader election.
 *
 * ## Contract
 * - This value is a best-effort snapshot at the time of query.
 * - To determine whether a lock can be acquired, use each elector's atomic acquire path rather than [state].
 * - When [status] is [LeaderStatus.Empty], [leader] is `null`.
 * - When [status] is [LeaderStatus.Occupied], [leader] is not `null`.
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
         * Creates a state snapshot with no leader.
         */
        fun empty(lockName: String): LeaderState =
            LeaderState(lockName, LeaderStatus.Empty)

        /**
         * Creates a state snapshot with an active leader.
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

    /** Whether there is currently no leader. */
    val isEmpty: Boolean get() = status == LeaderStatus.Empty

    /** Whether a leader is currently elected. */
    val isOccupied: Boolean get() = status == LeaderStatus.Occupied
}

package io.bluetape4k.leader

/**
 * Represents the current occupancy state of a single leader election.
 *
 * ## Contract
 * - [Empty] means there is currently no leader.
 * - [Occupied] means a leader is currently elected and holds a lease.
 *
 * ```kotlin
 * val state = election.state("daily-job")
 * if (state.status == LeaderStatus.Occupied) {
 *     println(state.leader?.leaderId)
 * }
 * ```
 */
enum class LeaderStatus {
    /** There is currently no leader. */
    Empty,

    /** A leader is currently elected. */
    Occupied,
}

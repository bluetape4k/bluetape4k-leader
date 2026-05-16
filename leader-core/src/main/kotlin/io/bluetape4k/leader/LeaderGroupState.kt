package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Immutable data class holding the current state of a leader group.
 *
 * Shared across [LeaderGroupElector] and [SuspendLeaderGroupElector] implementations.
 *
 * ```kotlin
 * val state = election.state("batch-lock")
 * println("active leaders: ${state.activeCount}/${state.maxLeaders}")
 * if (state.isFull) println("all slots occupied")
 * ```
 *
 * @property lockName the lock name used to identify the leader group
 * @property maxLeaders maximum number of concurrent leaders allowed
 * @property activeCount number of currently active (running) leaders
 * @property leaders list of node/slot leases currently elected as leaders. May be empty depending on the backend.
 *   Event-stream projections such as `leaderGroupStateFlow()` always keep this empty because revoke events do not
 *   identify the released slot.
 */
data class LeaderGroupState(
    val lockName: String,
    val maxLeaders: Int,
    val activeCount: Int,
    val leaders: List<LeaderLease> = emptyList(),
): Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        lockName.requireNotBlank("lockName")
        maxLeaders.requireGe(1, "maxLeaders")
        activeCount.requireInRange(0, maxLeaders, "activeCount")
        leaders.size.requireInRange(0, maxLeaders, "leaders.size")
    }

    /**
     * Number of remaining slots available to accept a new leader.
     *
     * ```kotlin
     * val state = LeaderGroupState(lockName = "job", maxLeaders = 3, activeCount = 1)
     * val slots = state.availableSlots
     * // slots == 2
     * ```
     */
    val availableSlots: Int get() = maxLeaders - activeCount

    /**
     * Whether the maximum leader count has been reached and no additional election is possible.
     *
     * ```kotlin
     * val state = LeaderGroupState(lockName = "job", maxLeaders = 3, activeCount = 3)
     * val full = state.isFull
     * // full == true
     * ```
     */
    val isFull: Boolean get() = activeCount >= maxLeaders

    /**
     * Whether there are currently no active leaders.
     *
     * ```kotlin
     * val state = LeaderGroupState(lockName = "job", maxLeaders = 3, activeCount = 0)
     * val empty = state.isEmpty
     * // empty == true
     * ```
     */
    val isEmpty: Boolean get() = activeCount == 0
}

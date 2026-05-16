package io.bluetape4k.leader

/**
 * Common interface defining state query methods for leader group election.
 *
 * All leader group election interfaces — [AsyncLeaderGroupElector], [LeaderGroupElector],
 * [VirtualThreadLeaderGroupElector], etc. — extend this interface to share state query methods.
 *
 * ```kotlin
 * val election: LeaderGroupElectionState = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val state = election.state("batch-job")  // LeaderGroupState
 * ```
 */
interface LeaderGroupElectionState {

    /** Maximum number of concurrent leaders allowed. */
    val maxLeaders: Int

    /**
     * Returns the number of currently active (running) leaders for [lockName].
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val count = election.activeCount("batch-job")
     * // count == 0  (when nobody is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return current active leader count (approximate)
     */
    fun activeCount(lockName: String): Int

    /**
     * Returns the number of remaining slots available to accept a new leader for [lockName].
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val slots = election.availableSlots("batch-job")
     * // slots == 3  (when nobody is running)
     * ```
     *
     * @param lockName the lock name to query
     * @return available slot count (approximate)
     */
    fun availableSlots(lockName: String): Int

    /**
     * Returns the current [LeaderGroupState] for [lockName].
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val state = election.state("batch-job")
     * // state.maxLeaders == 3
     * // state.activeCount == 0
     * // state.isEmpty == true
     * ```
     *
     * @param lockName the lock name to query
     * @return current leader group state snapshot
     */
    fun state(lockName: String): LeaderGroupState
}

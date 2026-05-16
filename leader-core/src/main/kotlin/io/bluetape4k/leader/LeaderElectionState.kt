package io.bluetape4k.leader

/**
 * Common interface defining state query methods for a single leader election.
 *
 * ## Contract
 * [state] is a best-effort snapshot at the time of query. Do not use the query result to decide
 * whether to run an action — use the atomic acquire path of each elector's `runIfLeader` family instead.
 *
 * ```kotlin
 * val state = election.state("daily-job")
 * println(state.status)
 * ```
 */
interface LeaderElectionState {

    /**
     * Returns the current single-leader state snapshot for [lockName].
     *
     * The default implementation returns an empty snapshot for source compatibility with external implementations.
     * Override this method if the backend can provide real owner metadata.
     *
     * @param lockName the lock name to query
     * @return current leader state snapshot
     */
    fun state(lockName: String): LeaderState =
        LeaderState.empty(lockName)
}

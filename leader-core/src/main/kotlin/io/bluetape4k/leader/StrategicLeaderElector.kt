package io.bluetape4k.leader

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Leader election interface that supports pluggable election strategies.
 *
 * Unlike [LeaderElector], uses a candidate-list-based election approach instead of distributed lock contention.
 *
 * ## Election Flow
 * 1. Register a candidate with [registerCandidate].
 * 2. Query the current candidate list with [listCandidates].
 * 3. Determine the winner with [ElectionStrategy.elect].
 * 4. If this node is the winner, execute the action; otherwise return null.
 *
 * ## Distributed Consistency Note
 * When all nodes apply a deterministic strategy to the same candidate list, they compute the same winner.
 * However, inconsistencies caused by differences in candidate registration/query timing must be handled by the backend implementation.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val election = LocalStrategicLeaderElector("node-1")
 *
 * // 1. Register candidate — renew at heartbeat interval in distributed environments
 * election.registerCandidate("nightly-job", CandidateInfo(election.nodeId))
 *
 * // 2. ScoredElectionStrategy + IdleTimeScorer — elects the node that has been idle the longest
 * val strategy = ScoredElectionStrategy(IdleTimeScorer)
 *
 * // 3. Run action if elected, otherwise null
 * val result: Report? = election.runIfLeader("nightly-job", strategy) {
 *     generateNightlyReport()
 * }
 * ```
 */
interface StrategicLeaderElector {

    /** Node identifier represented by this instance. */
    val nodeId: String

    /**
     * Registers or refreshes a candidate.
     *
     * [ttl] = [Duration.ZERO] means no TTL (ignored by local implementations).
     * For distributed backends, setting at least twice the heartbeat interval is recommended.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** Unregisters a candidate. */
    fun unregisterCandidate(lockName: String, nodeId: String)

    /** Returns the current list of candidates registered for [lockName]. */
    fun listCandidates(lockName: String): List<CandidateInfo>

    /**
     * Records an action result back into the candidate info.
     * Increments [CandidateInfo.successCount] or [CandidateInfo.failureCount] based on [result].
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * Elects a leader using the strategy and executes [action] only if this node is the winner.
     *
     * @param lockName the election identifier
     * @param strategy the election strategy
     * @param options election options (waitTime, leaseTime)
     * @param action the action to run when elected
     * @return [action] result, or `null` if election failed or another node is the winner
     */
    fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions = LeaderElectionOptions.Default,
        action: () -> T,
    ): T?
}

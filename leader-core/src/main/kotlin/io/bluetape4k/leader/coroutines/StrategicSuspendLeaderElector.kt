package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine-based leader election interface that supports pluggable election strategies.
 *
 * Suspend variant of [io.bluetape4k.leader.StrategicLeaderElector].
 *
 * `CancellationException` must be re-propagated to the caller when the coroutine is cancelled while [action] is running.
 */
interface StrategicSuspendLeaderElector {

    /** Node identifier represented by this instance. */
    val nodeId: String

    /**
     * Registers or refreshes a candidate.
     *
     * [ttl] = [Duration.ZERO] means no TTL (ignored by local implementations).
     */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** Unregisters a candidate. */
    suspend fun unregisterCandidate(lockName: String, nodeId: String)

    /** Returns the current list of candidates registered for [lockName]. */
    suspend fun listCandidates(lockName: String): List<CandidateInfo>

    /**
     * Records an action result back into the candidate info.
     */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * Elects a leader using the strategy and executes the suspend [action] only if this node is the winner.
     *
     * @param lockName the election identifier
     * @param strategy the election strategy
     * @param options election options
     * @param action the suspend action to run when elected
     * @return [action] result, or `null` if election failed or another node is the winner
     */
    suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions = LeaderElectionOptions.Default,
        action: suspend () -> T,
    ): T?
}

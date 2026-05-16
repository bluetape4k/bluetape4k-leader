package io.bluetape4k.leader.strategy

/**
 * Strategy interface for electing a leader from a list of candidates.
 *
 * ## Behavior / Contract
 * - All implementations must be deterministic: the same [candidates] input must always produce
 *   the same [ElectionResult]. This allows each node in a distributed environment to compute
 *   the same winner independently without coordination.
 * - Default tie-breaker: ascending [CandidateInfo.registeredAt], then lexicographic [CandidateInfo.nodeId].
 *
 * ## Built-in strategies
 * - [io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy] — earliest-registered candidate
 * - [io.bluetape4k.leader.strategy.strategies.RandomElectionStrategy] — deterministic random (requires shared seed)
 * - [io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy] — highest-scoring candidate
 *
 * ## Custom strategy example
 * ```kotlin
 * // Elect only candidates whose nodeId ends with an even digit
 * object EvenNodeStrategy : ElectionStrategy {
 *     override fun elect(candidates: List<CandidateInfo>): ElectionResult {
 *         val even = candidates.filter { it.nodeId.last().digitToIntOrNull()?.rem(2) == 0 }
 *         val winner = even.minByOrNull { it.registeredAt } ?: return ElectionResult.EMPTY
 *         val eliminations = candidates.filter { it.nodeId != winner.nodeId }
 *             .map { Elimination(it, "odd nodeId") }
 *         return ElectionResult(winner, eliminations)
 *     }
 * }
 * ```
 */
interface ElectionStrategy {

    /**
     * Elects a leader from [candidates] and returns an [ElectionResult] containing
     * the winner and the elimination reason for each losing candidate.
     *
     * @param candidates candidates participating in this election
     * @return election result (winner + list of eliminations with reasons)
     */
    fun elect(candidates: List<CandidateInfo>): ElectionResult
}

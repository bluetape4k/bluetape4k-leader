package io.bluetape4k.leader.strategy

/**
 * Computes an election-priority score for a candidate node.
 *
 * A higher score increases the likelihood that the candidate is elected leader.
 * Used by [ScoredElectionStrategy].
 *
 * ## Behavior / Contract
 * - Implementations must be pure: the same inputs must always produce the same output.
 * - Scores from different scorers combined in a [io.bluetape4k.leader.strategy.scorers.WeightedScorer]
 *   should use a compatible scale (e.g., 0.0–100.0).
 *
 * ## Example
 * ```kotlin
 * val scorer = CandidateScorer { candidate, _ ->
 *     candidate.successRate * 100.0
 * }
 * ```
 */
fun interface CandidateScorer {

    /**
     * Computes the score for [candidate] relative to [all] candidates in the current election.
     *
     * @param candidate the candidate to score
     * @param all all candidates participating in this election (available for relative comparison)
     * @return priority score — higher means more likely to be elected
     */
    fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double
}

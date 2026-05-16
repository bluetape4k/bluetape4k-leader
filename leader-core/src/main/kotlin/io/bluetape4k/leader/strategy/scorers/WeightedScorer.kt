package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.support.requireNotEmpty

/**
 * A composite [CandidateScorer] that combines multiple scorers by applying individual weights
 * and summing the results.
 *
 * ## Behavior / Contract
 * - Weights do not need to sum to 1.0; each scorer's result is scaled by its weight and summed.
 * - All weights must be positive (enforced at construction).
 *
 * ## Example
 * ```kotlin
 * val scorer = WeightedScorer(
 *     IdleTimeScorer to 0.6,
 *     SuccessRateScorer to 0.4,
 * )
 * ```
 *
 * @property scorers list of (scorer, weight) pairs
 */
class WeightedScorer(
    val scorers: List<Pair<CandidateScorer, Double>>,
) : CandidateScorer {

    init {
        scorers.requireNotEmpty("scorers")
        require(scorers.all { (_, w) -> w > 0.0 }) {
            "All scorer weights must be positive: ${scorers.map { it.second }}"
        }
    }

    constructor(vararg scorers: Pair<CandidateScorer, Double>) : this(scorers.toList())

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        scorers.sumOf { (scorer, weight) -> scorer.score(candidate, all) * weight }
}

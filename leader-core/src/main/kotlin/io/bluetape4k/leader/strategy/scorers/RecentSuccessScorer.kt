package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * A [CandidateScorer] that assigns a higher score to the node that most recently completed
 * a successful execution.
 *
 * ## Scoring rules
 * - Last execution succeeded: score normalized 0.0–100.0 against the latest successful completion
 *   time in the candidate pool.
 * - Last execution failed, or no execution history: 0.0.
 *
 * Favors re-electing the node that succeeded last time, increasing the likelihood of consecutive
 * successes (sticky-leader pattern).
 *
 * ## Example
 * ```kotlin
 * // Sticky leader: prefer the last successful node, with partial load balancing
 * val scorer = WeightedScorer(
 *     RecentSuccessScorer to 0.7,
 *     IdleTimeScorer to 0.3,
 * )
 * val strategy = ScoredElectionStrategy(scorer)
 * ```
 */
object RecentSuccessScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (candidate.successCount == 0L) return 0.0
        val lastCompletion = candidate.lastCompletionTime ?: return 0.0
        val lastStart = candidate.lastStartTime
        if (lastStart != null && lastCompletion.isBefore(lastStart)) return 0.0

        val successfulCompletions = all.mapNotNull { c ->
            if (c.successCount > 0) c.lastCompletionTime?.toEpochMilli() else null
        }
        if (successfulCompletions.isEmpty()) return 0.0
        val minEpoch = successfulCompletions.min()
        val maxEpoch = successfulCompletions.max()
        if (maxEpoch == minEpoch) return 100.0
        return (lastCompletion.toEpochMilli() - minEpoch).toDouble() / (maxEpoch - minEpoch) * 100.0
    }
}

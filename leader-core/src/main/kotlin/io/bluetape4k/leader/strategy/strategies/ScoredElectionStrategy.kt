package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * An [ElectionStrategy] that elects the candidate with the highest score computed by [scorer].
 *
 * ## Behavior / Contract
 * - Tie-breaking when scores are equal: ascending [CandidateInfo.registeredAt],
 *   then lexicographic [CandidateInfo.nodeId].
 *
 * @property scorer the [CandidateScorer] used to rank candidates
 */
class ScoredElectionStrategy(val scorer: CandidateScorer) : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val scores = candidates.associateWith { scorer.score(it, candidates) }
        val maxScore = scores.values.maxOrNull() ?: return ElectionResult.EMPTY
        val topCandidates = candidates.filter { scores[it] == maxScore }
        if (topCandidates.isEmpty()) return ElectionResult.EMPTY
        val winner = topCandidates.minWith(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
        val winnerScore = scores.getValue(winner)
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c ->
                val score = scores.getValue(c)
                val reason = if (score < winnerScore) {
                    "score below winner (%.2f < %.2f)".format(score, winnerScore)
                } else {
                    "tied score — ranked lower by registeredAt/nodeId (score: %.2f)".format(score)
                }
                Elimination(c, reason)
            }
        return ElectionResult(winner, eliminations, scores.mapKeys { it.key.nodeId })
    }
}
